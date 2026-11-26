#!/usr/bin/env python3
"""Aggregate one Android boottime trace per request without publishing raw logs."""

import argparse
import hashlib
import json
from pathlib import Path
import re

from summarize_render_benchmark import distribution

RECORD = re.compile(r'^(\d+) (\d+) ([A-Z]\w*) (\d+)(?: (.*))?$')
STAGES = set('T0 T1 T1q T2 T3 T3w T3m T4m T4 T5 T5w T5e T6 T6u T7 T7l T8 T8w T9 T9p T10 T11 U0 U1 U2 U3'.split())
INTERVALS = {
    'dispatch': ('T0', 'T1'),
    'queue': ('T1', 'T2'),
    'ioDispatch': ('T3', 'T3w'),
    'mapAsset': ('T3m', 'T4m'),
    'scenarioRead': ('T4m', 'T4'),
    'readTotal': ('T3w', 'T4'),
    'loadToSession': ('T4', 'T5'),
    'sessionDispatch': ('T5', 'T5w'),
    'sessionInit': ('T5w', 'T5e'),
    'sessionToPublish': ('T5e', 'T6'),
    'publishToUi': ('T6', 'T6u'),
    'uiToHudCommit': ('T6u', 'T7'),
    'uiToHudPlacement': ('T6u', 'T7l'),
    'geometryDispatch': ('T8', 'T8w'),
    'geometryPrep': ('T8w', 'T9'),
    'geometryToDraw': ('T9', 'T10'),
    'readyPublish': ('T0', 'T6'),
    'firstDraw': ('T0', 'T10'),
    'mapReadyCallback': ('T0', 'T11'),
    'hudCommitToU1': ('T7', 'U1'),
    'firstDrawToU3': ('T10', 'U3'),
    'observedMapStatus': ('T0', 'U1'),
    'observedAttributionWait': ('U1', 'U2'),
    'observedPlayButtonWait': ('U2', 'U3'),
    'originalVisibleReady': ('T0', 'U3'),
}


def parse_run(path):
    groups = {}
    raw = path.read_bytes()
    for line in raw.decode().splitlines():
        if not line.strip():
            continue
        record = RECORD.fullmatch(line.strip())
        if record is None:
            raise ValueError(f'Malformed probe record in {path}')
        request, run_id, stage, stamp, detail = record.groups()
        stages = groups.setdefault(int(request), {})
        if stage not in STAGES or stage in stages:
            raise ValueError(f'Unexpected or repeated stage {stage} in {path}')
        stages[stage] = (int(run_id), int(stamp), detail)
    if len(groups) != 3:
        raise ValueError(f'Expected three distinct requests in {path}, found {len(groups)}')
    rows = []
    for request, stages in sorted(groups.items()):
        if stages.keys() != STAGES:
            raise ValueError(f'{path}: missing {sorted(STAGES - stages.keys())}')
        run_id = stages['T2'][0]
        if run_id <= 0 or any(run not in (0, run_id) for run, _, _ in stages.values()):
            raise ValueError(f'{path}: request spans different sessions')
        if stages['T0'][1] != request or stages['T1'][2] != 'OL03' or stages['T2'][2] != 'OL03':
            raise ValueError(f'{path}: request identity differs from benchmark selection')
        if [stages[key][2] for key in ('T1q', 'T4m', 'T4', 'T6', 'T7')] != ['true', 'O07', 'O07', 'OL03', 'O07']:
            raise ValueError(f'{path}: scenario, map or queue contract differs')
        relative = {stage: round((stamp - request) / 1e6, 4) for stage, (_, stamp, _) in stages.items()}
        if min(relative.values()) < 0 or not (relative['U0'] <= relative['U1'] <= relative['U2'] <= relative['U3']):
            raise ValueError(f'{path}: invalid boottime order')
        size, mode, lod, scale = stages['T10'][2].split(',')
        rows.append({
            'runId': run_id,
            'viewportPx': [int(value) for value in size.split('x')],
            'camera': mode, 'lod': lod, 'scale': float(scale),
            'assetCacheHit': stages['T3m'][2] == 'O07,true',
            'geometryCacheHit': stages['T8'][2] == 'true',
            'eventMsFromRequest': dict(sorted(relative.items())),
            'intervalsMs': {
                name: round(relative[end] - relative[start], 4)
                for name, (start, end) in INTERVALS.items()
            },
        })
    return {'set': path.parent.name, 'sha256': hashlib.sha256(raw).hexdigest(), 'repetitions': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sets', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if len({path.parent.name for path in args.sets}) != len(args.sets):
        parser.error('duplicate set names')
    sets = [parse_run(path) for path in args.sets]
    builds = {}
    for entry in sets:
        side = re.search(r'([ABC])\d+$', entry['set'])
        if not side:
            parser.error(f'expected a name ending in build and index such as A1: {entry["set"]}')
        builds.setdefault(side[1], []).extend(entry['repetitions'])
    summary = {
        side: {key: distribution([row['intervalsMs'][key] for row in rows]) for key in INTERVALS}
        for side, rows in builds.items()
    }
    args.output.write_text(json.dumps({
        'source': 'diagnostic-only repeated RefactorMapBenchmark switchO10ToO07',
        'clock': 'Android elapsedRealtimeNanos (CLOCK_BOOTTIME), relative to T0',
        'unit': 'ms', 'sets': sets, 'summary': summary,
    }, allow_nan=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
