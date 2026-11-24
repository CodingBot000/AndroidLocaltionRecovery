"""Hand-authored synthetic layouts. Endpoints use node IDs, not coordinates."""

def layout(name, label_ko, nodes, roads, parks=(), water=(), crossings=None):
    node_rows = [dict(id=key, point=list(value[:2]), level=value[2] if len(value) > 2 else 0)
                 for key, value in nodes.items()]
    edge_rows = []
    for row in roads:
        edge_id, start, end, grade, *options = row
        structure, points = options if options else ("SURFACE", ())
        edge_rows.append(dict(id=edge_id, start=start, end=end,
                              points=[nodes[start][:2], *points, nodes[end][:2]],
                              roadClass=grade, structure=structure,
                              level={"ELEVATED": 1, "BRIDGE": 1, "TUNNEL": -1}.get(structure, 0)))
    transitions = {edge["id"]: [nxt["id"] for nxt in edge_rows if edge["end"] == nxt["start"]
                                and edge["start"] != nxt["end"]] for edge in edge_rows}
    if crossings:
        transitions.update(crossings)
    visual = [dict(type="PARK", points=polygon) for polygon in parks]
    visual += [dict(type="WATER", points=polygon) for polygon in water]
    labels = [dict(text=name, point=[nodes[next(iter(nodes))][0] + 12, nodes[next(iter(nodes))][1] + 30])]
    for edge in edge_rows:
        if edge["structure"] != "SURFACE":
            start, end = edge["points"][0], edge["points"][-1]
            labels.append(dict(text=edge["structure"].title(),
                               point=[(start[0] + end[0]) / 2 + 8, (start[1] + end[1]) / 2 + 20]))
    return dict(schemaVersion=2, name=name, nameKo=label_ko, nodes=node_rows, edges=edge_rows,
                transitions=transitions, polygons=visual, labels=labels)


def rect(x, y, width, height):
    return [(x, y), (x + width, y), (x + width, y + height), (x, y + height)]


M01 = layout("Grid District", "격자 지구", {
    "a": (0, 0), "b": (280, 0), "c": (280, 280), "d": (600, 280), "e": (900, 280),
    "f": (600, 0), "g": (0, 280), "h": (900, 0), "i": (600, 560), "j": (900, 560),
}, [
    ("A", "a", "b", "ARTERIAL"), ("B", "b", "c", "COLLECTOR"), ("C", "c", "d", "COLLECTOR"),
    ("D", "d", "e", "ARTERIAL"), ("E", "b", "f", "ARTERIAL"), ("F", "f", "d", "LOCAL"),
    ("G", "g", "c", "LOCAL"), ("H", "a", "g", "LOCAL"), ("I", "f", "h", "ARTERIAL"),
    ("J", "h", "e", "LOCAL"), ("K", "d", "i", "COLLECTOR"), ("L", "i", "j", "LOCAL"),
    ("M", "e", "j", "LOCAL"), ("N", "g", "a", "LOCAL"),
], parks=[rect(345, 105, 150, 100), rect(645, 350, 150, 120)])
M02 = layout("Express Junction", "고속화도로 분기", {
    "a": (0, -180), "b": (320, 0), "c": (850, 20), "d": (1440, 70), "e": (1960, 130),
    "f": (1190, -260), "g": (1660, -370), "h": (2190, -330), "i": (590, -290),
}, [
    ("A", "a", "b", "RAMP", "SURFACE", ((150, -165), (250, -70))),
    ("B", "b", "c", "EXPRESSWAY"),
    ("C", "c", "d", "EXPRESSWAY"), ("D", "d", "e", "EXPRESSWAY"),
    ("X", "c", "f", "RAMP", "ELEVATED", ((970, -2), (1100, -108))),
    ("Y", "f", "g", "ARTERIAL"), ("Z", "g", "h", "ARTERIAL"), ("S", "i", "f", "COLLECTOR"),
], parks=[rect(480, 100, 430, 210), rect(1370, 170, 480, 200)])
M03 = layout("Basin Tunnel", "분지 터널", {
    "a": (0, 0), "b": (510, 45), "c": (1450, 45), "d": (1850, 335),
    "e": (350, -280), "f": (1170, -360), "g": (1650, -250),
}, [
    ("A", "a", "b", "ARTERIAL", "SURFACE", ((210, 0), (420, 20))),
    ("T", "b", "c", "ARTERIAL", "TUNNEL", ((850, 45), (1150, 45))),
    ("C", "c", "d", "COLLECTOR", "SURFACE", ((1620, 65), (1750, 152))),
    ("S", "a", "e", "LOCAL"), ("V", "e", "f", "COLLECTOR"), ("W", "f", "g", "LOCAL"),
], parks=[[(470,-180),(1490,-165),(1540,225),(1200,460),(650,430)]])
M04 = layout("Service Frontage", "본선과 서비스도로", {
    "a": (0, -100), "b": (200, 0), "c": (900, 0), "d": (1280, 175),
    "p": (200, 12), "q": (900, 12), "e": (1230, -130), "r": (0, 150),
}, [
    ("A", "a", "b", "ARTERIAL"), ("B", "b", "c", "ARTERIAL"),
    ("C", "c", "d", "ARTERIAL"), ("J", "r", "p", "LOCAL"), ("P", "p", "q", "LOCAL"),
    ("K", "q", "d", "LOCAL"), ("X", "c", "e", "COLLECTOR"),
], parks=[rect(400, 95, 260, 150), rect(950, -250, 260, 105)])
M05 = layout("Stacked Exchange", "입체 교차로", {
    "a": (0, 0), "b": (600, 0, 1), "c": (1120, 0, 1), "d": (1650, 180),
    "s": (600, -400), "k": (600, 0), "n": (600, 405), "e": (850, 360), "f": (1140, 270),
}, [
    ("A", "a", "b", "EXPRESSWAY", "ELEVATED", ((300, 0),)),
    ("B", "b", "c", "EXPRESSWAY", "ELEVATED", ()),
    ("C", "c", "d", "ARTERIAL"),
    ("N", "s", "k", "ARTERIAL"), ("O", "k", "n", "ARTERIAL"),
    ("X", "b", "e", "RAMP", "ELEVATED", ((720, 25), (790, 190))),
    ("Y", "e", "f", "COLLECTOR"), ("Z", "f", "c", "RAMP", "SURFACE", ((1190, 110),)),
], parks=[rect(930, -410, 390, 260), rect(180, 95, 255, 180)])
M06 = layout("Two Bridges", "두 교량 수변", {
    "a": (0, 0), "b": (400, 40), "c": (850, 40), "d": (1120, 295),
    "e": (0, 500), "f": (400, 500), "g": (850, 500), "h": (1220, 560),
}, [
    ("A", "a", "b", "COLLECTOR"), ("B", "b", "c", "ARTERIAL", "BRIDGE", ()),
    ("C", "c", "d", "COLLECTOR"), ("E", "e", "f", "COLLECTOR"),
    ("F", "f", "g", "ARTERIAL", "BRIDGE", ()), ("G", "g", "h", "COLLECTOR"),
    ("H", "a", "e", "LOCAL"), ("I", "d", "g", "LOCAL"),
], parks=[rect(60, 145, 275, 225), rect(930, 50, 200, 120)],
    water=[[(450,-290),(725,-290),(825,730),(500,730)]])
M07 = layout("Garden Roads", "정원 생활도로", {
    "a": (0, 0), "b": (245, 125), "c": (450, 0), "d": (670, 160),
    "e": (245, 410), "f": (680, -200), "g": (940, -210),
}, [
    ("A", "a", "b", "LOCAL", "SURFACE", ((70, 10), (155, 70))),
    ("B", "b", "c", "LOCAL", "SURFACE", ((335, 105), (385, 35))),
    ("C", "c", "d", "COLLECTOR", "SURFACE", ((535, 30), (600, 102))),
    ("N", "b", "e", "LOCAL", "SURFACE", ((268, 250),)),
    ("F", "c", "f", "LOCAL"), ("G", "f", "g", "LOCAL"),
], parks=[rect(20, 150, 165, 240), rect(460, 145, 140, 195)])
M08 = layout("Contour Pass", "능선 굽잇길", {
    "a": (0, 0), "b": (260, 220), "c": (620, 410), "d": (1100, 540),
    "e": (690, 765), "f": (930, 910),
}, [
    ("A", "a", "b", "COLLECTOR", "SURFACE", ((100, 10), (155, 70), (135, 128), (205, 200))),
    ("B", "b", "c", "COLLECTOR", "SURFACE", ((355, 259), (405, 328), (486, 365))),
    ("T", "c", "d", "ARTERIAL", "TUNNEL", ((810, 470), (980, 498))),
    ("N", "c", "e", "LOCAL", "SURFACE", ((610, 560), (650, 620))),
    ("O", "e", "f", "LOCAL"),
], parks=[[(-80,130),(185,300),(315,590),(135,675),(-120,480)],
           [(390,565),(560,640),(600,920),(440,875)],
           [(730,150),(1040,200),(1190,420),(990,415),(800,350)]])
M09 = layout("Civic Corridor", "복합 교통 회랑", {
    "a": (0, 0), "b": (350, 0), "c": (670, 85), "d": (1050, 230),
    "e": (1440, 230), "f": (585, -255), "g": (1030, -190), "h": (1510, -70), "j": (1030, 580),
}, [
    ("A", "a", "b", "ARTERIAL"), ("B", "b", "c", "ARTERIAL"), ("C", "c", "d", "ARTERIAL"),
    ("D", "d", "e", "ARTERIAL"), ("F", "b", "f", "COLLECTOR"),
    ("G", "f", "g", "LOCAL"), ("H", "g", "d", "COLLECTOR", "SURFACE", ((1155, -55),)),
    ("I", "g", "h", "LOCAL"), ("J", "h", "e", "LOCAL"), ("K", "d", "j", "COLLECTOR"),
], parks=[rect(110, 100, 235, 150), rect(1080, 330, 335, 155)])
M10 = layout("Crossfield Circuit", "복합 장거리 회랑", {
    "a": (0, 0), "b": (300, 0), "c": (550, 160), "d": (1000, 205),
    "e": (1440, 205), "f": (1810, 210), "g": (2190, 235), "h": (2540, 430),
    "x": (1990, -65), "l": (135, 220), "m": (495, -290), "n": (1240, -250), "p": (2170, 610),
}, [
    ("A", "a", "b", "COLLECTOR"), ("B", "b", "c", "ARTERIAL", "SURFACE", ((365, 12),(465, 88))),
    ("C", "c", "d", "EXPRESSWAY"), ("T", "d", "e", "EXPRESSWAY", "TUNNEL", ()),
    ("E", "e", "f", "EXPRESSWAY"), ("F", "f", "g", "EXPRESSWAY"),
    ("G", "g", "h", "ARTERIAL", "BRIDGE", ((2360, 282),)),
    ("X", "f", "x", "RAMP", "SURFACE", ((1860, 160), (1920, 15))),
    ("Y", "x", "g", "RAMP", "ELEVATED", ((2080, -40), (2170, 54))),
    ("L", "a", "l", "LOCAL"), ("M", "b", "m", "LOCAL"), ("N", "m", "n", "COLLECTOR"),
    ("P", "g", "p", "COLLECTOR"),
], parks=[rect(165, 75, 130, 140), rect(925, 270, 490, 230), rect(1480, -165, 315, 220)],
    water=[[(2260,70),(2340,45),(2480,575),(2360,615)]])

MAPS = {f"M{index:02}": value for index, value in enumerate(
    (M01, M02, M03, M04, M05, M06, M07, M08, M09, M10), 1)}
