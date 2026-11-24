"""Author deterministic synthetic routes and apply errors after freezing truth."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "fixtures"
ROADS = {
    "A": [(0, 0), (100, 0), (200, 0)],
    "B": [(200, 0), (360, 0), (600, 0)],
    "C": [(600, 0), (1000, 0), (1220, 0), (1300, 40), (1300, 220)],
    "D": [(200, 0), (240, 80), (560, 80), (600, 0)],
    "P": [(0, 24), (200, 24)],
}

def motion(first, last, step=1000, velocity=20, start=0, y=0, road="A"):
    return [(t, start + (t-first)*velocity/1000, y, road) for t in range(first, last+1, step)]

def event(t, x, y=0, *, acc=6, speed=20, heading=90, label="NORMAL", received=None, id=None):
    return dict(id=id or f"g{t}", measured=t, received=t if received is None else received,
                x=x, y=y, accuracy=acc, speed=speed, heading=heading, label=label)

def expected(time, reason, count=1):
    return dict(time=time, reason=reason, count=count)

def scenario(id, title, truth, *, drop=(), edits=None, add=(), duration=None, checks=(), error=None,
             group="evaluation", shift=0, recovery_times=()):
    edits = edits or {}
    observations = [
        event(t, x + shift, y, **edits.get(t, {}))
        for t, x, y, road in truth if t not in drop
    ] + [dict(row, x=row["x"] + shift) for row in add]
    payload = dict(
        schemaVersion=1, scenarioId=id, title=title,
        duration=truth[-1][0] if duration is None else duration,
        roads=[dict(id=key, points=[[x + shift, y] for x, y in points],
                    tunnel=key == "B") for key, points in ROADS.items() if key != "P" or id in {"S10", "S06B", "S04"}],
        plannedRoute=["A", "B", "C"],
        observations=observations,
        groundTruth=[dict(time=t, x=x + shift, y=y, road=road) for t,x,y,road in truth],
        expected=list(checks),
        expectedRecoveryAtMs=list(recovery_times),
    )
    if error == "ROAD_GEOMETRY":
        payload["roads"][0]["points"][1] = payload["roads"][0]["points"][0]
    elif error == "REPLAY_TIME":
        payload["observations"][0]["received"] = -1
    elif error == "JSON":
        (ROOT / group / f"{id}.json").write_text('{"observations":[1,NaN]}\n', encoding="utf-8")
        return
    (ROOT / group / f"{id}.json").write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")

straight = motion(0, 8000, start=10)
branch = [(0,170,0,"A"), (1000,190,0,"A"), (2000,205,10,"D"), (3000,218,36,"D"),
          (4000,234,68,"D"), (5000,255,80,"D"), (6000,280,80,"D"), (7000,305,80,"D")]
returning = branch + [(8000,350,80,"D"), (9000,395,80,"D"), (10000,440,80,"D"),
                     (11000,485,80,"D"), (12000,530,80,"D"), (13000,560,80,"D"),
                     (14000,575,50,"D"), (15000,590,20,"D"), (16000,605,0,"C"),
                     (17000,627,0,"C"), (18000,650,0,"C"), (19000,670,0,"C")]
scenario("S01", "Straight road and curve",
         [(0,1160,0,"C"), (1000,1180,0,"C"), (2000,1200,0,"C"), (3000,1225,2.5,"C"),
          (4000,1250,15,"C"), (5000,1275,27.5,"C"), (6000,1300,44,"C"),(7000,1300,69,"C")],
         edits={6000:dict(heading=0),7000:dict(heading=0)}, checks=[expected(None,"ACCEPTED",8),expected(None,"REROUTE_RECOMMENDED",0)])
scenario("S02", "GPS position jump", straight, drop={3000}, add=[event(3000,620,label="INVALID")],
         checks=[expected(3000,"IMPLAUSIBLE_MOVE"),expected(4000,"ACCEPTED")])
scenario("S03", "Stop, slow movement and missing heading", motion(0,6000,start=75,velocity=0),
         edits={t:dict(speed=0,heading=None if t%2000==0 else 280) for t in range(0,7000,1000)},
         checks=[expected(None,"ACCEPTED",7),expected(None,"IMPLAUSIBLE_MOVE",0)])
scenario("S04", "Poor accuracy and urban jitter", motion(0,6000,start=30,velocity=0),
         edits={2000:dict(acc=38,label="LOW_QUALITY"),3000:dict(acc=65,label="LOW_QUALITY")},
         checks=[expected(2000,"LOW_QUALITY"),expected(3000,"ACCURACY_EXCEEDED"),expected(None,"REROUTE_RECOMMENDED",0)])
scenario("S05", "Duplicate, ordering and delayed samples", straight,
         add=[event(1500,39,id="g1000",label="INVALID"),event(2000,50,id="same",label="INVALID"),
              event(2500,60,received=3600,label="INVALID"),event(4500,100,received=8000,label="INVALID")],
         drop={5000,6000,7000,8000},duration=8200,
         checks=[expected(1500,"DUPLICATE_ID"),expected(2000,"MEASUREMENT_ORDER"),expected(3600,"MEASUREMENT_ORDER"),expected(8000,"DELIVERY_DELAY")])
short = motion(0,12000,start=8,velocity=12)
scenario("S06", "Short signal loss", short, drop={3000,4000,5000,6000},
         edits={t:dict(speed=12) for t,_,_,_ in short},
         checks=[expected(5000,"SIGNAL_LOST"),expected(5000,"DR_STARTED"),expected(9000,"SIGNAL_RECOVERED")], recovery_times=[7000])
scenario("S06B", "Latest accepted point has no reliable road", [(0,25,0,"A"),(1000,45,0,"A"),(2000,65,0,"A")],
         drop={2000},add=[event(2000,65,18,heading=None)],duration=7000,
         checks=[expected(2000,"AMBIGUOUS"),expected(5000,"SIGNAL_LOST"),expected(None,"DR_STARTED",0)])
long = motion(0,26000,start=280,velocity=9,road="B")
scenario("S07", "Long tunnel outage", long, drop=set(range(3000,22000,1000)),
         edits={t:dict(speed=9) for t,_,_,_ in long},
         checks=[expected(5000,"DR_STARTED"),expected(17000,"DR_LIMIT"),expected(24000,"SIGNAL_RECOVERED")], recovery_times=[22000])
stop = [(t, min(145, 35+t*0.02), 0, "A") for t in range(0,17000,1000)]
scenario("S08", "Vehicle brakes during outage", stop, drop=set(range(3000,11000,1000)),
         edits={t:dict(speed=20 if t<3000 else 0) for t,_,_,_ in stop},
         checks=[expected(5000,"DR_STARTED"),expected(13000,"SIGNAL_RECOVERED")], recovery_times=[11000])
scenario("S09", "Recovery candidate resets and expires", motion(0,15000,start=33,velocity=8),
         drop={2000,3000,4000,6000,7000,8000,11000},
         edits={10000:dict(acc=45,label="LOW_QUALITY")},
         checks=[expected(5000,"RECOVERY_PENDING"),expected(7000,"RECOVERY_TIMEOUT"),expected(10000,"LOW_QUALITY"),expected(14000,"SIGNAL_RECOVERED")], recovery_times=[5000])
scenario("S09B", "Candidate gap precedes old-candidate movement",
         [(0,0,0,"A"),(5000,0,0,"A"),(7200,200,0,"A")],
         edits={5000:dict(received=6000),7200:dict(received=7500)},duration=7800,
         checks=[expected(7500,"RECOVERY_GAP"),expected(7500,"RECOVERY_PENDING"),expected(7500,"IMPLAUSIBLE_MOVE",0)])
scenario("S10", "Ambiguous parallel roads", motion(0,4000,start=30,velocity=10,y=12),
         edits={t:dict(heading=None) for t in range(0,5000,1000)},
         checks=[expected(None,"AMBIGUOUS",5),expected(None,"REROUTE_RECOMMENDED",0)])
scenario("S11", "True branch departure", branch, checks=[expected(None,"REROUTE_RECOMMENDED",1)])
scenario("S12", "Single false departure jump", straight,drop={3000},
         add=[event(3000,350,80,label="INVALID")],
         checks=[expected(3000,"IMPLAUSIBLE_MOVE"),expected(None,"REROUTE_RECOMMENDED",0)])
scenario("S13", "Confirmed return to the planned route", returning,
         edits={t:dict(heading=None) for t,_,_,_ in returning},checks=[expected(None,"REROUTE_RECOMMENDED",1),expected(None,"ROUTE_RETURNED",1)])
scenario("S14", "No usable initial observation", motion(0,6000,start=20),
         drop={1000,2000,3000,4000,5000,6000}, edits={0:dict(acc=55,label="LOW_QUALITY")},
         checks=[expected(0,"LOW_QUALITY"),expected(None,"ACCEPTED",0),expected(None,"DR_STARTED",0)])
scenario("S15", "Replay controls and end boundary", straight, duration=8150,
         checks=[expected(None,"ACCEPTED",9),expected(8150,"RUN_COMPLETED")])
off_loss = branch[:2] + [(t,250+(t-6000)*0.02,80,"D") for t in range(6000,13000,1000)]
scenario("S16", "Real departure while signal is lost", off_loss,
         checks=[expected(4000,"SIGNAL_LOST"),expected(8000,"SIGNAL_RECOVERED"),expected(10000,"REROUTE_RECOMMENDED")], recovery_times=[6000])
scenario("S17", "Malformed observation values", straight, edits={2000:dict(acc=-1,label="INVALID"),
         3000:dict(received=2500,label="INVALID")},checks=[expected(2000,"INVALID_ACCURACY"),expected(2500,"FUTURE_MEASUREMENT")])
for suffix, error in [("A","ROAD_GEOMETRY"),("B","REPLAY_TIME"),("C","JSON")]:
    scenario("S17"+suffix, "Malformed fixture", straight, error=error)
demo = [(0,60,0,"A"),(1000,80,0,"A"),(2000,100,0,"A"),(3000,120,0,"A"),(4000,140,0,"A"),
        (5000,160,0,"A"),(6000,180,0,"A"),(7000,205,10,"D"),(8000,220,40,"D"),
        (9000,235,70,"D"),(10000,260,80,"D"),(11000,280,80,"D"),(12000,300,80,"D"),
        (13000,320,80,"D"),(14000,340,80,"D"),(15000,360,80,"D")]
scenario("DEMO", "Jump, signal loss and recovery", demo, drop={3000,4000,5000,6000,7000,8000,9000},
         add=[event(3000,670,label="INVALID"),event(7000,205,10,acc=45,label="LOW_QUALITY")],
         checks=[expected(3000,"IMPLAUSIBLE_MOVE"),expected(5000,"DR_STARTED"),
                 expected(12000,"SIGNAL_RECOVERED"),expected(14000,"REROUTE_RECOMMENDED")],
         group="development",shift=27,recovery_times=[10000])
