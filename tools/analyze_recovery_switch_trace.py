#!/usr/bin/env python3
"""Analyze switch traces with Perfetto's Python Trace Processor package."""

import argparse
import hashlib
import json
from pathlib import Path
import re

from perfetto.trace_processor import TraceProcessor

TARGET = 'lab.recovery.app.benchmark'
ACTIVITY = f'{TARGET}/lab.recovery.app.MainActivity'
NS_PER_MS = 1_000_000


def analyze(path):
    with TraceProcessor(trace=str(path)) as trace:
        return read_trace(trace, path)


def read_trace(trace, path):
    bounds, = trace.query("SELECT ts, ts + dur AS end FROM slice WHERE name = 'measureBlock' AND dur > 0")
    start, end = bounds.ts, bounds.end
    target_threads = f"""
      SELECT t.utid, CASE WHEN t.tid = p.pid THEN 'UI'
        WHEN t.name = 'RenderThread' THEN 'RenderThread'
        WHEN t.name GLOB 'Jit*' THEN 'JIT'
        WHEN t.name GLOB 'DefaultDispatch*' THEN 'worker'
        ELSE 'other' END AS role
      FROM thread t JOIN process p USING (upid) WHERE p.name = '{TARGET}'
    """
    # Thread-state rows are disjoint on each individual thread. Worker members may run in parallel.
    scheduling = [row.__dict__ for row in trace.query(f"""
      WITH target AS ({target_threads})
      SELECT role, COUNT(DISTINCT utid) AS threads,
       SUM(CASE WHEN state = 'Running' THEN MIN(ts+dur,{end})-MAX(ts,{start}) ELSE 0 END)/1e6 AS runningMs,
       SUM(CASE WHEN state IN ('R','R+') THEN MIN(ts+dur,{end})-MAX(ts,{start}) ELSE 0 END)/1e6 AS runnableMs
      FROM thread_state JOIN target USING (utid)
      WHERE dur > 0 AND ts < {end} AND ts+dur > {start} AND role != 'other'
      GROUP BY role ORDER BY role
    """)]
    compose = [row.__dict__ for row in trace.query(f"""
      WITH target AS ({target_threads})
      SELECT s.name, COUNT(*) AS slices,
        SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start}))/1e6 AS inclusiveMs
      FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN target USING (utid)
      WHERE s.ts < {end} AND s.ts+s.dur > {start} AND s.dur > 0
        AND (s.name IN ('Compose:recompose','Compose:applyChanges')
          OR s.name GLOB '*GC: Wait For*' OR s.name GLOB '*Concurrent copying*')
      GROUP BY s.name ORDER BY s.name
    """)]
    frames, = trace.query(f"""
      SELECT COUNT(DISTINCT a.surface_frame_token) AS surfaceTokens,
        COUNT(DISTINCT CASE WHEN a.jank_type LIKE '%App Deadline Missed%' THEN a.surface_frame_token END) AS appDeadlineMisses
      FROM actual_frame_timeline_slice a JOIN process p USING (upid)
      WHERE a.ts >= {start} AND a.ts < {end} AND p.name = '{TARGET}' AND a.layer_name LIKE '%{ACTIVITY}%'
    """)
    stages = diagnostic_stamps(trace, path, start, end)
    with path.open('rb') as source:
        sha256 = hashlib.file_digest(source, 'sha256').hexdigest()
    return {
        'id': path.stem.split('_202')[0],
        'sha256': sha256,
        'measureBlockMs': (end - start) / NS_PER_MS,
        'scheduling': scheduling,
        'composeAndGcSlices': compose,
        'timeline': frames.__dict__,
        'firstDrawFrame': join_first_frame(trace, stages),
        'mapStatusLookup': external_lookup(trace, stages),
        'timelineOverrunByLegMs': split_timeline(trace, end, stages),
    }


def diagnostic_stamps(trace, path, start, end):
    log = path.with_name('switch.log')
    if not log.exists():
        return None
    clock, = trace.query("SELECT int_value FROM metadata WHERE name = 'trace_time_clock_id'")
    snapshots, = trace.query("SELECT COUNT(*) AS n FROM clock_snapshot WHERE clock_id = 6 AND ts = clock_value")
    if clock.int_value != 6 or snapshots.n == 0:
        raise ValueError(f'{path}: no matching Android BOOTTIME trace clock')
    events = [line.split(maxsplit=4) for line in log.read_text().splitlines() if line]
    request, = [row[0] for row in events if row[2] == 'T0' and start <= int(row[3]) < end]
    return {row[2]: int(row[3]) for row in events if row[0] == request}


def external_lookup(trace, stages):
    if stages is None:
        return None
    start, end = stages['U0'], stages['U1']
    idle, = trace.query(f"""
      SELECT COUNT(*) AS n, COALESCE(SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start})),0)/1e6 AS totalMs,
        COALESCE(MAX(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start})),0)/1e6 AS maxMs
      FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING (utid) JOIN process p USING (upid)
      WHERE s.ts < {end} AND s.ts+s.dur > {start} AND s.dur > 0 AND p.name='lab.recovery.benchmark'
        AND s.name='UiDevice#waitForIdle'
    """)
    return idle.__dict__


def split_timeline(trace, end, stages):
    if stages is None:
        return None
    start, turn = stages['T0'], stages['U3']
    rows = trace.query(f"""
      SELECT CASE WHEN a.ts < {turn} THEN 'forward' ELSE 'return' END as leg,
        (a.ts + a.dur - e.ts - e.dur)/1e6 AS overrunMs
      FROM actual_frame_timeline_slice a JOIN process p USING (upid)
      JOIN expected_frame_timeline_slice e ON e.upid=a.upid AND e.surface_frame_token=a.surface_frame_token
      WHERE a.ts >= {start} AND a.ts < {end} AND p.name='{TARGET}' AND a.layer_name LIKE '%{ACTIVITY}%'
        AND a.dur > 0 AND e.dur > 0
      GROUP BY a.upid, a.surface_frame_token HAVING COUNT(*) = 1 ORDER BY a.ts
    """)
    result = {'forward': [], 'return': []}
    for row in rows:
        result[row.leg].append(row.overrunMs)
    return result


def join_first_frame(trace, stages):
    if stages is None:
        return None
    draw = stages['T10']
    containing = list(trace.query(f"""
      SELECT s.name FROM slice s JOIN thread_track tt ON tt.id=s.track_id
      JOIN thread t USING (utid) JOIN process p USING (upid)
      WHERE s.name GLOB 'Choreographer#doFrame *' AND p.name='{TARGET}' AND t.tid=p.pid
        AND s.ts <= {draw} AND s.ts+s.dur > {draw}
    """))
    if len(containing) != 1 or not (token := re.fullmatch(r'Choreographer#doFrame (\d+)', containing[0].name)):
        return {'unmatched': 'first draw has no unique Choreographer frame token'}
    apps = list(trace.query(f"""
      SELECT a.display_frame_token, a.ts + a.dur AS end, a.present_type, a.jank_type, a.upid
      FROM actual_frame_timeline_slice a JOIN process p USING (upid)
      WHERE p.name='{TARGET}' AND a.layer_name LIKE '%{ACTIVITY}%'
        AND a.surface_frame_token={token[1]} AND a.dur > 0
    """))
    if len(apps) != 1:
        return {'unmatched': f'{len(apps)} completed app rows for first draw token'}
    app = apps[0]
    expected = list(trace.query(f"""
      SELECT ts + dur AS end FROM expected_frame_timeline_slice
      WHERE upid={app.upid} AND surface_frame_token={token[1]} AND dur > 0
    """))
    displays = list(trace.query(f"""
      SELECT a.ts + a.dur AS end, a.present_type FROM actual_frame_timeline_slice a JOIN process p USING (upid)
      WHERE p.name LIKE '%surfaceflinger' AND a.display_frame_token={app.display_frame_token} AND a.dur > 0
    """))
    return {
        'appFinishAfterDrawMs': (app.end - draw) / NS_PER_MS,
        'appOverrunMs': (app.end - expected[0].end) / NS_PER_MS if len(expected) == 1 else None,
        'appPresentType': app.present_type,
        'appJank': app.jank_type,
        'displayPresentAfterDrawMs': (displays[0].end - draw) / NS_PER_MS if len(displays) == 1 else None,
        'displayPresentFromRequestMs': (displays[0].end - stages['T0']) / NS_PER_MS if len(displays) == 1 else None,
        'observationAfterPresentMs': (stages['U3'] - displays[0].end) / NS_PER_MS if len(displays) == 1 else None,
        'displayPresentType': displays[0].present_type if len(displays) == 1 else None,
        'expectedRows': len(expected), 'displayRows': len(displays),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sets', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if len({folder.name for folder in args.sets}) != len(args.sets):
        parser.error('duplicate set names')
    report = {}
    for folder in args.sets:
        cases = sorted(folder.glob('*switchO10ToO07_iter*.perfetto-trace'))
        if len(cases) != 3:
            parser.error(f'{folder}: expected three switch traces')
        report[folder.name] = [analyze(path) for path in cases]
    args.output.write_text(json.dumps(report, allow_nan=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
