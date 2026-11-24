"""Choose replay courses on the exported, turn-aware graph. No runtime routing."""

from bisect import bisect_right
from itertools import combinations
import math

import networkx as nx
from pyproj import Transformer
from shapely import LineString, Point


class RegionGraph:
    def __init__(self, data, lon_lat):
        self.data = data
        self.edges = {road["id"]: road for road in data["edges"]}
        self.lines = {key: LineString(road["points"]) for key, road in self.edges.items()}
        self.lengths = {key: line.length for key, line in self.lines.items()}
        self.graph = nx.DiGraph()
        self.graph.add_nodes_from(self.edges)
        for edge, links in data["transitions"].items():
            for following in links:
                self.graph.add_edge(edge, following, cost=self.lengths[following], back=self.lengths[edge])
        x, y = Transformer.from_crs("EPSG:4326", data["projection"]["crs"], always_xy=True).transform(*lon_lat)
        ox, oy = data["projection"]["origin"]
        self.anchor = Point(x-ox,y-oy)
        self.distances = {key: line.distance(self.anchor) for key, line in self.lines.items()}
        self.bounds = data["projection"]["bounds"]

    def length(self, route):
        return sum(self.lengths[key] for key in route)

    def physical(self, route):
        return {self.edges[key]["physicalId"] for key in route}

    def fits(self, route):
        xmin,ymin,xmax,ymax = self.bounds
        return all(xmin+65 <= x <= xmax-65 and ymin+65 <= y <= ymax-65
                   for key in route for x,y in self.edges[key]["points"])

    def reachable(self, edge, reverse=False, budget=2800):
        network = self.graph.reverse(copy=False) if reverse else self.graph
        _, paths = nx.single_source_dijkstra(network, edge, cutoff=budget, weight="back" if reverse else "cost")
        return [route[::-1] if reverse else route for route in paths.values()]

    def straight(self, target, grades, structure=None, shift=0):
        ids = [key for key,item in self.edges.items() if item["roadClass"] in grades and
               (structure is None or item["structure"] == structure)]
        longest = structure in {"TUNNEL", "BRIDGE"}
        ids.sort(key=lambda k: (self.distances[k] - (self.lengths[k] * .8 if longest else 0), k))
        score, result = math.inf, None
        for center in ids[shift:shift+18]:
            size = self.lengths[center]
            each = max(60, (target-size)/2)
            before = self.reachable(center, reverse=True, budget=max(each*2, 440))
            after = self.reachable(center, budget=max(each*2, 440))
            penalty = lambda p: sum(self.lengths[k] * .40 for k in p if self.edges[k]["roadClass"] not in grades)
            def fit(path):
                return abs(self.length(path)-size-each) + penalty(path)
            for prefix in sorted(before,key=fit)[:12]:
                for suffix in sorted(after,key=fit)[:12]:
                    route = prefix[:-1]+suffix
                    meters = self.length(route)
                    if len(route) != len(self.physical(route)) or not self.fits(route):
                        continue
                    if structure and (len(prefix) < 2 or len(suffix) < 2):
                        continue
                    objective = abs(meters-target)+self.distances[center]*.12+penalty(route)*.30
                    if structure == "TUNNEL":
                        tunnel_m = sum(self.lengths[k] for k in route if self.edges[k]["structure"]=="TUNNEL")
                        if tunnel_m < 520:
                            continue
                        objective -= min(tunnel_m, target*.76)*.15
                    if objective < score:
                        score,result = objective,route
        if result is None:
            raise ValueError(f"No valid route in {self.data['mapId']} for {target}m ({structure})")
        return result

    def diverge(self, target, *, require_ramp, rejoin=True, prefix_target=None, shift=0):
        fork_edges = [key for key in self.edges if self.graph.out_degree(key) >= 2 and
                      self.edges[key]["roadClass"] in {"EXPRESSWAY","ARTERIAL","COLLECTOR","RAMP"}]
        fork_edges.sort(key=lambda k: (self.distances[k], k))
        best, chosen = math.inf, None
        for fork in fork_edges[shift:shift+58]:
            branches = list(self.graph.successors(fork))
            if require_ramp and not any(self.edges[edge]["roadClass"] == "RAMP" for edge in branches):
                continue
            before = self.reachable(fork, reverse=True, budget=target*.80)
            prefix_m = prefix_target or target*.34
            prefixes = sorted(before, key=lambda path: abs(self.length(path)-prefix_m))[:8]
            forward = {}
            for branch in branches:
                forward[branch] = {path[-1]:path for path in self.reachable(branch, budget=target*.76)}
            end_cache = {}
            for left,right in combinations(branches, 2):
                if require_ramp and self.edges[right]["roadClass"] != "RAMP" and self.edges[left]["roadClass"] != "RAMP":
                    continue
                if require_ramp and self.edges[left]["roadClass"] == "RAMP":
                    left,right=right,left
                if rejoin:
                    tails = [(forward[left][end][:-1],forward[right][end][:-1],end)
                             for end in sorted(forward[left].keys() & forward[right].keys())]
                else:
                    expected, actual = (
                        sorted(forward[edge].values(), key=lambda path: abs(self.length(path)-target*.64))[:6]
                        for edge in (left,right))
                    tails = [(a,b,None) for a in expected for b in actual]
                for planned,actual,join in tails:
                    if not planned or not actual or self.physical(planned)&self.physical(actual):
                        continue
                    a,b = self.length(planned),self.length(actual)
                    if min(a,b) < 135 or max(a,b) > target*.73:
                        continue
                    if join is None:
                        endings = [[]]
                    else:
                        ending_budget = target*.65
                        end_m = max(180, target-prefix_m-(a+b)/2)
                        if join not in end_cache:
                            end_cache[join] = self.reachable(join, budget=ending_budget)
                        endings = sorted(end_cache[join], key=lambda p: abs(self.length(p)-end_m))[:5]
                    for prefix in prefixes:
                        for ending in endings:
                            detour, route = prefix+actual+ending, prefix+planned+ending
                            if any(len(self.physical(p)) != len(p) or not self.fits(p) for p in (route,detour)):
                                continue
                            length1,length2 = self.length(route),self.length(detour)
                            if min(length1,length2) < target*.65 or max(length1,length2) > target*1.27:
                                continue
                            score = abs(length1-target)+abs(length2-target)+self.distances[fork]*.36
                            score += abs(self.length(prefix)-prefix_m)*.5
                            if score < best:
                                best,chosen = score,(route,detour)
                if best < 120:
                    return chosen
        if chosen is None:
            raise ValueError(f"No valid diversion in {self.data['mapId']} (ramp={require_ramp}, rejoin={rejoin})")
        return chosen


def drive(region, route, duration, stops, seed):
    """Piecewise-linear arc length across actual OSM edges, sampled every 200 ms."""
    segments, stations = [], [0.0]
    for key in route:
        points = region.edges[key]["points"]
        for a,b in zip(points,points[1:]):
            distance = math.dist(a,b)
            segments.append((key,a,b,distance))
            stations.append(stations[-1]+distance)
    weights = []
    for tick in range(duration*5):
        t = tick/5
        ramp = min(1,t/3,(duration-t)/3)
        for start,end in stops:
            ramp = min(ramp,max(0,(start-t)/4,(t-end)/4))
        weights.append(ramp*(1+.12*math.sin(t*.23+seed)))
    pace = stations[-1]/sum(weights)
    truth, velocity = [], []
    traveled = 0.0
    for tick, weight in enumerate(weights+[0]):
        i = min(len(segments)-1,bisect_right(stations,traveled)-1)
        key,a,b,length = segments[i]
        fraction = min(1,(traveled-stations[i])/length)
        dx,dy = b[0]-a[0],b[1]-a[1]
        truth.append(dict(time=tick*200,x=round(a[0]+fraction*dx,4),y=round(a[1]+fraction*dy,4),road=key))
        velocity.append((round(weight*pace*5,4),round(math.degrees(math.atan2(dx,dy))%360,4)%360))
        traveled += weight*pace
    assert max(speed for speed,_ in velocity) < 44
    return truth,velocity
