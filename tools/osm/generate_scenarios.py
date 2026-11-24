"""Generate replay observations and independent input-derived expectations on OSM roads."""

import argparse
from itertools import groupby
import math
import json
from pathlib import Path
import sys

from shapely import Point
from shapely.ops import nearest_points

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"extended"))
from driving import receiver
from generate import CASES, CATEGORY_KO, HELD_OUT

from import_maps import digest, write_json
from regions import REGIONS
from routes import RegionGraph, drive

LOCAL = {"LOCAL", "COLLECTOR"}
CITY = LOCAL | {"ARTERIAL"}
HIGHWAY = {"EXPRESSWAY", "RAMP"}
TARGETS = [1100, 2420, 800, 1230, 1400, 2700, 2990, 1510, 1050, 1040, 2500, 1180, 1700, 650, 1350, 1760, 1570, 2790]


def scenario(graph, number, independent):
    _, duration, _, title, ko, kind = CASES[number-1]
    shift = 2 if independent else 0
    seed = 72200+number if independent else 70100+number
    target = TARGETS[number-1]+(50 if independent else 0)
    representative = number == 18
    if number in (11,13,16,18):
        route,actual = graph.diverge(target, require_ramp=number in (11,), rejoin=number != 11,
                                     prefix_target=target*.62 if representative else None, shift=shift)
    else:
        structure = "TUNNEL" if number in (6,7) else "BRIDGE" if number in (8,17) else None
        grades = HIGHWAY if number in (2,6,7) else LOCAL if number in (3,9,14) else CITY
        if number == 2:
            grades = {"RAMP"}
        if structure == "BRIDGE":
            grades = CITY | HIGHWAY
        route = actual = graph.straight(target, grades, structure, shift)
    stops = [(19,24)] if number in (1,3,14,15) else [(42,49)] if number == 8 else [(14,17)] if representative else []
    truth,speeds = drive(graph,actual,duration,stops,seed)
    faults,expected,recovery = {},[],[]

    def put(time, *values):
        faults.setdefault(time,[]).extend(values)

    def interval(first,last,fault):
        for time in range(first,last):
            put(time,fault)

    def expect(reason, second=None, count=1):
        expected.append(dict(reason=reason, count=count, time=None if second is None else second*1000))

    def outage(first,last, restore=None):
        interval(first,last,"drop")
        expect("SIGNAL_LOST",first+2)
        if restore is not False:
            actual_start = restore if restore is not None else last
            recovery.append(actual_start*1000)
            expect("SIGNAL_RECOVERED",actual_start+2)

    def tunnel_window(span):
        ticks = [row for row in truth[::5]]
        groups = [list(group) for key,group in groupby(range(1,duration-5),
                     lambda i: graph.edges[ticks[i]["road"]]["structure"]=="TUNNEL") if key]
        viable = [group for group in groups if len(group) >= span+3]
        if not viable:
            raise ValueError(f"{graph.data['mapId']} has insufficient tunnel for {span}s")
        window = max(viable,key=len)
        start = window[1+(len(window)-span-2)//2]
        return start,start+span

    planned = set(route)
    departure = next((idx for idx,row in enumerate(truth[::5]) if row["road"] not in planned), None)
    if number == 2:
        for t in (32+shift,68+shift):
            put(t,"jump")
            expect("IMPLAUSIBLE_MOVE",t)
    if number == 3:
        interval(14,32,"heading")
    if number == 4:
        interval(21,29,"shake")
        interval(38+shift,49+shift,"bias")
        interval(54+shift,56+shift,"quality")
        expect("LOW_QUALITY",count=2)
    if number == 5:
        for at,action,reason in [(25,"duplicate","DUPLICATE_ID"),(41,"order","MEASUREMENT_ORDER"),
                                 (56,"collision","MEASUREMENT_ORDER")]:
            put(at+shift,action)
            expect(reason,at+shift)
        interval(61+shift,65+shift,"drop")
        put(65+shift,"delay")
        put(66+shift,"drop")
        expect("DELIVERY_DELAY",66+shift)
        expect("SIGNAL_LOST",63+shift)
        expect("SIGNAL_RECOVERED",69+shift)
        recovery.append((67+shift)*1000)
    if number in (6,7):
        start,end = tunnel_window(8 if number == 6 else 22)
        outage(start,end)
        expect("DR_STARTED")
        if number == 7:
            expect("DR_LIMIT")
    if number == 8:
        outage(39+shift,56+shift)
    if number == 9:
        base = 55+shift
        outage(43+shift,base,restore=False)
        put(base,"quality")
        interval(base+2,base+4,"drop")
        put(base+4,"poor")
        expect("LOW_QUALITY",base)
        expect("RECOVERY_TIMEOUT",base+3)
        expect("SIGNAL_RECOVERED",base+7)
        recovery.append((base+1)*1000)
    if number == 14:
        interval(0,6+shift,"drop")
        interval(6+shift,9+shift,"quality")
        expect("LOW_QUALITY",count=3)
        expect("ACCEPTED",9+shift)
    if number == 16:
        # Lose signal across the turn, then reacquire on the off-route artery before its short rejoin links.
        outage(departure-6,departure+4)
    if number in (11,13,16,18):
        expect("REROUTE_RECOMMENDED")
        if number != 11:
            expect("ROUTE_RETURNED")
    if number == 17:
        for t,fault,reason in [(22,"negative_speed","INVALID_SPEED"),(41,"future","FUTURE_MEASUREMENT"),
                               (75,"poor","ACCURACY_EXCEEDED")]:
            put(t+shift,fault)
            expect(reason,t+shift)
    if representative:
        put(29,"jump")
        expect("IMPLAUSIBLE_MOVE",29)
        interval(42,44,"quality")
        expect("LOW_QUALITY",count=2)
        start = max(48, departure-33)
        outage(start,start+21,restore=start+22)
        put(start+21,"poor")
        expect("ACCURACY_EXCEEDED",start+21)
    expect("RUN_COMPLETED",duration)
    if number not in (11,13,16,18):
        expect("REROUTE_RECOMMENDED",count=0)

    fixes = receiver(truth,speeds,faults,seed)
    if number in (10,12):
        best = nearby_parallel(graph,truth)
        if best is None:
            raise ValueError("Barmbek does not contain a nearby distinct parallel way on the route")
        at,point,neighbor = best
        observation = fixes[at]
        if number == 10:
            observation.update(x=round((observation["x"]+neighbor.x)/2,4),
                               y=round((observation["y"]+neighbor.y)/2,4),heading=None,accuracy=16)
        else:
            observation.update(x=round(neighbor.x,4),y=round(neighbor.y,4))
    id = f"OE{graph.data['mapId'][1:]}" if independent else "ODEMO_LONG" if representative else f"OL{number:02}"
    description=f"{graph.data['name']} · {kind}. Simulated vehicle and GPS on offline OpenStreetMap geometry."
    description_ko=f"{graph.data['nameKo']} · {CATEGORY_KO[kind]}. 오프라인 OpenStreetMap 도로에서 가상 차량과 GPS를 재생합니다."
    return (
        dict(id=id,mapId=graph.data["mapId"],duration=duration*1000,title=title,titleKo=ko,
             kind=kind,kindKo=CATEGORY_KO[kind],description=description,descriptionKo=description_ko,
             evaluation=independent,representative=representative),
        dict(schemaVersion=3,scenarioId=id,title=title,mapId=graph.data["mapId"],duration=duration*1000,
             routeVersion=1,seed=seed,plannedRoute=route,actualRoute=actual,
             observations=fixes,groundTruth=truth,expected=expected,
             expectedRecoveryAtMs=recovery,expectedFinalSignal="TRACKING"),
    )


def nearby_parallel(graph, truth):
    for at in range(30,70):
        row = truth[at*5]
        edge = graph.edges[row["road"]]
        pos = Point(row["x"],row["y"])
        line = graph.lines[row["road"]]
        vector = (line.coords[-1][0]-line.coords[0][0], line.coords[-1][1]-line.coords[0][1])
        heading = math.atan2(*vector)
        for key,item in graph.edges.items():
            if item["physicalId"] == edge["physicalId"] or item["level"] != edge["level"] or key.endswith("_R"):
                continue
            other=graph.lines[key]
            if other.length < 45 or not 8 < other.distance(pos) < 32:
                continue
            axis=(other.coords[-1][0]-other.coords[0][0],other.coords[-1][1]-other.coords[0][1])
            if abs(math.sin(heading-math.atan2(*axis))) > .25:
                continue
            return at,pos,nearest_points(pos,other)[1]
    return None


def generate(output):
    regions={}
    for id,_,name,ko,_,anchor in REGIONS:
        path=output/"maps"/f"{id}.json"
        regions[id]=RegionGraph(json.loads(path.read_text()),anchor)
    entries=[]
    for number,independent in [(n,False) for n in range(1,19)]+[(n,True) for n in HELD_OUT]:
        region=regions[f"O{CASES[number-1][0]:02}"]
        entry,fixture=scenario(region,number,independent)
        fixture["mapChecksum"]=digest(output/"maps"/f"{entry['mapId']}.json")
        write_json(output/("evaluation" if independent else "scenarios")/f"{entry['id']}.json",fixture)
        entries.append(entry)
        print(entry["id"],entry["mapId"],len(fixture["actualRoute"]),round(region.length(fixture["actualRoute"])),flush=True)
    write_json(output/"catalog.json",dict(schemaVersion=3,entries=entries,
               maps=[dict(id=id,name=name,nameKo=ko) for id,_,name,ko,_,_ in REGIONS]))


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,default=Path("fixtures/osm"))
    generate(parser.parse_args().output)
