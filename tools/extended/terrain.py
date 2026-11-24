"""Hand-authored districts, street frontages, and pedestrian paths for the synthetic maps."""

import math
from random import Random

from driving import distance_to_segment, inside

ROAD_CLEARANCE = {"LOCAL": 8, "COLLECTOR": 11, "ARTERIAL": 14, "EXPRESSWAY": 19, "RAMP": 13}

# Frontage endpoints are the parcel's street-facing edge. Positive depth extends to its left.
# Compact blocks, detached lots, and works yards have different footprints and spacing.
FRONTAGES = {
    "M01": [
        ((23, 25), (258, 25), 102, "COMMERCIAL"), ((254, 255), (25, 255), 92, "RESIDENTIAL"),
        ((23, 58), (23, 228), -76, "RESIDENTIAL"), ((255, -24), (24, -24), 108, "COMMERCIAL"),
        ((307, 25), (575, 25), 75, "COMMERCIAL"), ((574, 254), (308, 254), 76, "RESIDENTIAL"),
        ((309, 306), (565, 306), 116, "COMMERCIAL"), ((626, 307), (873, 307), 80, "COMMERCIAL"),
        ((870, 252), (628, 252), 88, "RESIDENTIAL"), ((627, 30), (870, 30), 80, "COMMERCIAL"),
        ((630, 532), (874, 532), 85, "RESIDENTIAL"), ((575, 541), (575, 324), -81, "RESIDENTIAL"),
        ((-26, 228), (-26, 36), -105, "RESIDENTIAL"),
    ],
    "M02": [
        ((37, -205), (154, -192), -62, "INDUSTRIAL"), ((226, -162), (303, -88), -69, "INDUSTRIAL"),
        ((114, -232), (319, -263), -76, "INDUSTRIAL"), ((383, -53), (607, -45), -94, "INDUSTRIAL"),
        ((664, -248), (971, -227), -90, "INDUSTRIAL"), ((1392, -332), (1610, -384), -84, "INDUSTRIAL"),
        ((1720, -403), (2050, -380), -90, "INDUSTRIAL"), ((1530, 145), (1800, 176), 98, "COMMERCIAL"),
    ],
    "M03": [
        ((34, 32), (244, 42), 79, "RESIDENTIAL"), ((520, -292), (880, -319), -87, "INDUSTRIAL"),
        ((1250, -378), (1540, -321), -74, "RESIDENTIAL"), ((1657, 107), (1785, 210), -71, "RESIDENTIAL"),
        ((1732, 237), (1824, 361), 81, "RESIDENTIAL"),
    ],
    "M04": [
        ((229, 42), (414, 41), 83, "INDUSTRIAL"), ((446, 41), (656, 41), 48, "COMMERCIAL"),
        ((677, 43), (872, 40), 84, "INDUSTRIAL"), ((866, -28), (656, -28), 99, "INDUSTRIAL"),
        ((622, -29), (248, -29), 87, "COMMERCIAL"), ((955, 91), (1160, 185), 73, "COMMERCIAL"),
        ((1040, -81), (1210, -144), -71, "INDUSTRIAL"), ((25, 178), (195, 103), 55, "RESIDENTIAL"),
    ],
    "M05": [
        ((51, -28), (326, -28), -105, "INDUSTRIAL"), ((566, 52), (565, 274), 87, "COMMERCIAL"),
        ((570, -359), (569, -94), 94, "INDUSTRIAL"), ((633, -330), (632, -98), -101, "INDUSTRIAL"),
        ((870, 384), (1110, 313), 85, "COMMERCIAL"), ((1248, 86), (1540, 189), 87, "COMMERCIAL"),
    ],
    "M06": [
        ((37, 34), (186, 51), 68, "RESIDENTIAL"), ((212, -8), (358, 10), -83, "COMMERCIAL"),
        ((30, 461), (210, 461), -83, "RESIDENTIAL"), ((233, 531), (382, 531), 91, "RESIDENTIAL"),
        ((882, 100), (1038, 245), 78, "RESIDENTIAL"), ((968, 487), (1172, 518), -80, "COMMERCIAL"),
        ((919, 551), (1165, 585), 67, "RESIDENTIAL"),
    ],
    "M07": [
        ((22, -27), (118, 14), -57, "RESIDENTIAL"), ((134, 21), (234, 84), -55, "RESIDENTIAL"),
        ((260, 144), (326, 126), 66, "RESIDENTIAL"), ((342, 111), (413, 45), 69, "RESIDENTIAL"),
        ((456, -30), (599, 70), -72, "RESIDENTIAL"), ((273, 192), (287, 291), -68, "RESIDENTIAL"),
        ((236, 270), (223, 394), 55, "RESIDENTIAL"), ((710, -227), (912, -232), -65, "RESIDENTIAL"),
    ],
    "M08": [
        ((17, -26), (104, -10), -62, "RESIDENTIAL"), ((205, 219), (245, 246), 59, "RESIDENTIAL"),
        ((326, 222), (410, 278), -66, "RESIDENTIAL"), ((656, 704), (740, 793), -61, "RESIDENTIAL"),
        ((798, 798), (909, 874), -54, "RESIDENTIAL"),
    ],
    "M09": [
        ((28, -25), (323, -25), -93, "COMMERCIAL"), ((384, 47), (627, 111), 85, "COMMERCIAL"),
        ((368, -42), (493, -179), -62, "COMMERCIAL"), ((425, -51), (576, -211), 62, "INDUSTRIAL"),
        ((646, 109), (885, 194), 84, "RESIDENTIAL"), ((760, 78), (989, 166), -78, "COMMERCIAL"),
        ((1076, 257), (1409, 257), 81, "COMMERCIAL"), ((1370, 205), (1111, 205), 96, "COMMERCIAL"),
        ((614, -283), (935, -233), -123, "INDUSTRIAL"), ((1064, -213), (1414, -127), -108, "INDUSTRIAL"),
        ((999, 543), (1011, 307), -65, "RESIDENTIAL"),
    ],
    "M10": [
        ((22, 23), (273, 23), 90, "COMMERCIAL"), ((273, -24), (20, -24), 114, "COMMERCIAL"),
        ((41, 111), (115, 228), 75, "RESIDENTIAL"), ((80, 99), (140, 195), -72, "RESIDENTIAL"),
        ((312, 31), (412, 61), 72, "COMMERCIAL"), ((433, 95), (523, 168), 63, "RESIDENTIAL"),
        ((352, -58), (471, -211), 88, "COMMERCIAL"), ((502, -336), (759, -329), -92, "INDUSTRIAL"),
        ((788, -289), (1182, -266), -95, "INDUSTRIAL"), ((598, 217), (942, 252), 92, "INDUSTRIAL"),
        ((1484, 171), (1778, 173), -86, "INDUSTRIAL"), ((1489, 246), (1765, 252), 111, "COMMERCIAL"),
        ((1848, 247), (2149, 273), 99, "INDUSTRIAL"), ((2131, 317), (2120, 566), 90, "RESIDENTIAL"),
        ((2322, 414), (2440, 486), -65, "RESIDENTIAL"), ((2468, 505), (2573, 555), 81, "RESIDENTIAL"),
    ],
}

DISTRICTS = {
    "M01": [("RESIDENTIAL", [(-130,16),(267,15),(267,263),(1,264),(-136,216)]),
            ("COMMERCIAL", [(293,19),(578,18),(581,259),(293,260)]),
            ("RESIDENTIAL", [(615,297),(898,297),(998,453),(922,568),(629,572)]),
            ("COMMERCIAL", [(-105,-153),(385,-161),(593,-92),(568,-19),(24,-16),(-116,-46)]),
            ("PARK", [(166,-187),(371,-182),(536,-139),(660,-172),(756,-276),(654,-342),(407,-376),
                      (212,-318),(121,-244)])],
    "M02": [("INDUSTRIAL", [(46,-229),(195,-228),(301,-169),(358,-103),(406,-162),(354,-274),(140,-325),(16,-280)]),
            ("INDUSTRIAL", [(65,-221),(653,-206),(982,-193),(1210,-224),(1144,-469),(261,-487)]),
            ("PARK", [(353,122),(704,92),(1058,126),(1356,238),(1251,460),(689,503),(277,310)])],
    "M03": [("PARK", [(280,119),(590,193),(838,254),(1418,153),(1558,254),(1464,498),(1184,592),(645,492),(468,330)]),
            ("PARK", [(482,-66),(789,-102),(1050,-96),(1436,-42),(1530,-139),(1206,-223),(636,-240),(433,-157)])],
    "M04": [("COMMERCIAL", [(204,-177),(914,-166),(871,-23),(225,-23)]),
            ("INDUSTRIAL", [(198,32),(900,32),(902,172),(620,192),(349,167),(199,149)])],
    "M05": [("INDUSTRIAL", [(125,-387),(567,-387),(567,-29),(125,-29)]),
            ("PARK", [(653,74),(708,121),(734,240),(714,330),(661,421),(623,395),(626,90)])],
    "M06": [("WATER", [(462,-610),(581,-421),(628,-232),(630,-43),(667,146),(730,323),(745,508),(842,677),(853,861),
                         (681,902),(658,702),(579,551),(596,367),(538,184),(508,-12),(483,-171),(418,-348),(343,-583)]),
            ("PARK", [(37,169),(165,116),(319,152),(376,246),(327,385),(183,413),(38,335)])],
    "M07": [("RESIDENTIAL", [(9,31),(116,59),(186,128),(223,211),(199,268),(104,256),(11,165)]),
            ("PARK", [(312,187),(411,118),(461,171),(542,275),(503,363),(405,382),(329,318)])],
    "M08": [("PARK", [(-156,58),(-26,149),(223,292),(345,480),(302,646),(137,711),(-148,526)]),
            ("PARK", [(451,510),(572,545),(600,676),(618,810),(552,963),(405,875),(359,658)]),
            ("PARK", [(658,124),(880,155),(1124,137),(1365,333),(1299,469),(1086,456),(807,360)])],
    "M09": [("INDUSTRIAL", [(385,-26),(489,-41),(608,-168),(614,-201),(587,-236),(521,-183),(445,-106)]),
            ("COMMERCIAL", [(368,34),(665,118),(933,212),(880,278),(646,196),(411,152)]),
            ("INDUSTRIAL", [(569,-436),(1031,-405),(1426,-314),(1507,-176),(1057,-207),(594,-269)])],
    "M10": [("COMMERCIAL", [(-93,-178),(243,-185),(538,-260),(602,-141),(397,-24),(-75,-16)]),
            ("RESIDENTIAL", [(-140,8),(218,10),(327,161),(438,302),(366,507),(99,527),(-123,327)]),
            ("INDUSTRIAL", [(441,-528),(938,-509),(1393,-380),(1302,-273),(1081,-296),(673,-320),(440,-314)]),
            ("PARK", [(671,265),(835,303),(995,394),(1188,420),(1420,358),(1744,438),(1819,605),(1658,764),
                       (1324,804),(1069,705),(786,706),(657,501)]),
            ("PARK", [(933,-80),(1074,-135),(1217,-113),(1451,-63),(1584,25),(1462,144),(1190,166),(1000,132)]),
            ("WATER", [(2105,-1820),(2225,-1830),(2361,-1210),(2378,-878),(2371,-554),(2321,-329),
                       (2338,-120),(2416,59),(2462,261),(2498,441),(2447,610),(2513,796),(2589,1084),
                       (2511,1460),(2681,2000),(2551,2000),(2371,1425),(2450,1090),(2396,875),
                       (2320,649),(2363,469),(2321,277),(2299,104),(2215,-74),(2201,-306),
                       (2245,-579),(2249,-853),(2241,-1187)]),
            ("RESIDENTIAL", [(1959,275),(2171,273),(2261,373),(2268,556),(2147,719),(1981,671)]),
            ("INDUSTRIAL", [(1465,-363),(1759,-461),(2058,-362),(2138,-235),(2064,-128),(1801,-177),(1534,-124)]),
            ("PARK", [(74,-324),(260,-256),(373,-386),(333,-562),(93,-583),(-53,-511)]),
            ("PARK", [(2493,502),(2774,528),(2850,690),(2723,811),(2567,743),(2531,632)]),
            ("PARK", [(264,538),(478,552),(716,660),(849,806),(1040,866),(1348,938),(1547,834),(1723,880),
                       (1826,1029),(1732,1167),(1338,1332),(941,1253),(605,1233),(381,1049),(133,938),(61,742)]),
            ("INDUSTRIAL", [(730,-624),(1130,-581),(1363,-541),(1593,-581),(1794,-735),(1688,-991),
                            (1285,-1072),(911,-963),(684,-793)]),
            ("PARK", [(1967,822),(2168,843),(2305,1031),(2247,1349),(2080,1305),(1907,1104)])],
}

WALKS = {
    "M01": [[(56,194),(99,160),(164,158),(206,183)],[(378,131),(452,143),(507,181),(532,207)]],
    "M02": [[(457,220),(596,173),(742,179),(896,245),(1019,322)]],
    "M03": [[(541,282),(703,351),(918,372),(1121,329),(1370,311)]],
    "M04": [[(414,114),(499,151),(599,153),(653,120)]],
    "M05": [[(659,110),(693,162),(681,253),(645,319)]],
    "M06": [[(46,231),(127,213),(196,244),(244,302),(310,319),(400,282)],[(873,153),(866,254),(910,331),(945,395)]],
    "M07": [[(317,261),(362,228),(417,258),(472,310),(469,353)]],
    "M08": [[(12,337),(93,362),(136,431),(203,462),(201,527)]],
    "M09": [[(114,165),(192,139),(263,184),(304,240)]],
    "M10": [[(627,467),(793,415),(908,461),(1044,539),(1170,558),(1300,512),(1469,524),(1644,641)],
            [(967,-114),(1085,-75),(1210,-31),(1338,-22),(1419,21),(1466,72)],
            [(2043,335),(2067,446),(2035,541),(2076,627)]],
}

ROAD_NAMES = {
    "M01": {"A":"Market Avenue","B":"Canal Street","C":"Civic Way","D":"Civic Way","E":"Market Avenue","F":"West Lane","G":"Civic Way","K":"Library Street"},
    "M02": {"A":"South Ramp","B":"Field Expressway","C":"Field Expressway","D":"Field Expressway","X":"East Exit","Y":"Depot Road"},
    "M03": {"A":"Basin Approach","T":"Basin Tunnel","C":"Ridge Exit","V":"Lower Road"},
    "M04": {"A":"Frontage Avenue","B":"Frontage Avenue","C":"Frontage Avenue","P":"Service Lane","K":"North Access"},
    "M05": {"A":"Crown Flyover","B":"Crown Flyover","N":"Underpass Road","O":"Underpass Road","X":"Loop Ramp","Y":"Exchange Road","Z":"Return Ramp"},
    "M06": {"A":"Bank Road","B":"South Bridge","C":"East Bank Road","F":"North Bridge","E":"Meadow Road","H":"West Quay"},
    "M07": {"A":"Garden Lane","B":"Garden Lane","C":"Orchard Road","N":"North Garden","F":"South Lane"},
    "M08": {"A":"Contour Road","B":"Contour Road","T":"Pass Tunnel","N":"Ridge Road"},
    "M09": {"A":"Civic Avenue","B":"Civic Avenue","C":"Civic Avenue","D":"Civic Avenue","F":"Depot Access","G":"Foundry Lane","H":"Park Link"},
    "M10": {"A":"Market Avenue","B":"Crossfield Way","C":"Field Expressway","T":"Crossfield Tunnel","E":"Field Expressway",
            "F":"Field Expressway","G":"East Bridge","X":"South Exit","Y":"Return Ramp","L":"Garden Road","M":"Depot Access","N":"Works Road","P":"North Bank"},
}


def pairs(points):
    return zip(points, points[1:] + points[:1])


def orientation(a, b, p):
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])


def intersects(a, b, c, d):
    if not all(max(min(a[k], b[k]), min(c[k], d[k])) <= min(max(a[k], b[k]), max(c[k], d[k])) + 1e-8 for k in (0, 1)):
        return False
    return (orientation(a, b, c) * orientation(a, b, d) <= 1e-8 and
            orientation(c, d, a) * orientation(c, d, b) <= 1e-8)


def segment_distance(a, b, c, d):
    if intersects(a, b, c, d):
        return 0
    return min(distance_to_segment(a, c, d), distance_to_segment(b, c, d),
               distance_to_segment(c, a, b), distance_to_segment(d, a, b))


def overlaps(first, second):
    if not all(max(min(p[k] for p in first), min(p[k] for p in second)) <=
               min(max(p[k] for p in first), max(p[k] for p in second)) for k in (0, 1)):
        return False
    return (inside(*first[0], second) or inside(*second[0], first) or
            any(intersects(a, b, c, d) for a, b in pairs(first) for c, d in pairs(second)))


def crosses_polygon(path, polygon):
    return any(inside(*point, polygon) for point in path) or any(
        intersects(a, b, c, d) for a, b in zip(path, path[1:]) for c, d in pairs(polygon))


def frontage(start, end, depth, kind, index, rng):
    length = math.dist(start, end)
    ux, uy = ((b - a) / length for a, b in zip(start, end))
    normal = (-uy, ux) if depth > 0 else (uy, -ux)
    depth = abs(depth)

    def transform(points):
        return [(round(start[0] + x * ux + y * normal[0], 2),
                 round(start[1] + x * uy + y * normal[1], 2)) for x, y in points]

    # A chamfered corner gives the street intersection extra space.
    outline = transform([(0, 8), (9, 0), (length - 9, 0), (length, 10),
                         (length - 4, depth - 4), (9, depth)])
    parcel = dict(type=kind, id=f"P{index:02}", points=outline)
    sizes = {"COMMERCIAL": (29, 58, 15, 26), "RESIDENTIAL": (14, 30, 13, 24), "INDUSTRIAL": (42, 87, 25, 45)}
    min_width, max_width, min_depth, max_depth = sizes[kind]
    buildings = []
    rows = (10, depth - 30) if kind == "COMMERCIAL" and depth > 74 else (13,)
    for y in rows:
        x = 14
        while x + min_width < length - 11:
            width = min(rng.uniform(min_width, max_width), length - 12 - x)
            height = min(rng.uniform(min_depth, max_depth), depth - y - 9)
            if height < 8:
                break
            shape = [(x,y), (x+width,y), (x+width,y+height), (x,y+height)]
            if rng.random() < 0.40:
                shape = [(x,y), (x+width,y), (x+width,y+height*.55), (x+width*.65,y+height*.55),
                         (x+width*.65,y+height), (x,y+height)]
            buildings.append(dict(type="BUILDING", parcelId=parcel["id"], points=transform(shape)))
            x += width + rng.uniform(7, 16)
    return parcel, buildings


def near_road(points, roads):
    xmin, ymin = (min(p[k] for p in points) for k in (0, 1))
    xmax, ymax = (max(p[k] for p in points) for k in (0, 1))
    for a, b, clearance in roads:
        if max(a[0], b[0])+clearance < xmin or min(a[0], b[0])-clearance > xmax:
            continue
        if max(a[1], b[1])+clearance < ymin or min(a[1], b[1])-clearance > ymax:
            continue
        if inside(*a, points) or any(segment_distance(a,b,c,d) < clearance for c,d in pairs(points)):
            return True
    return False


def road_segments(data):
    return [(a, b, ROAD_CLEARANCE[edge["roadClass"]])
            for edge in data["edges"] for a, b in zip(edge["points"], edge["points"][1:])]


def compose_terrain(data, map_id, seed):
    rng = Random(seed)
    # Keep compatible parks on maps where they are already bounded by the road network.
    park_rows = [p for p in data["polygons"] if p["type"] == "PARK" and map_id in {"M01", "M04", "M05", "M09"}]
    terrain = [dict(type=kind, points=points) for kind, points in DISTRICTS[map_id]] + park_rows
    natural = [p["points"] for p in terrain if p["type"] in {"PARK", "WATER"}]
    roads = road_segments(data)
    buildings = []
    walks = WALKS[map_id]
    for index, (a, b, depth, kind) in enumerate(FRONTAGES[map_id], 1):
        parcel, candidates = frontage(a, b, depth, kind, index, rng)
        accepted = [candidate for candidate in candidates
                    if not near_road(candidate["points"], roads)
                    and not any(overlaps(candidate["points"], n) for n in natural)
                    and not any(crosses_polygon(walk, candidate["points"]) for walk in walks)
                    and not any(overlaps(candidate["points"], other["points"]) for other in buildings)]
        if accepted:
            terrain.append(parcel)
            buildings.extend(accepted)
    data["visualVersion"] = 2
    data["polygons"] = terrain + buildings
    data["walkways"] = walks
    names = ROAD_NAMES[map_id]
    for edge in data["edges"]:
        if edge["id"] in names:
            edge["name"] = names[edge["id"]]
    label = data["labels"][0]
    district_points = {"M01": (69, 211), "M06": (90, 126), "M10": (380, 155)}
    label["point"] = district_points.get(map_id, label["point"])
    data["labels"] = [label]
    if map_id == "M10":
        data["labels"].append(dict(text="North Reserve", point=(981, 1014)))
    if map_id == "M06":
        data["labels"].append(dict(text="East Quay", point=(975, 366)))
