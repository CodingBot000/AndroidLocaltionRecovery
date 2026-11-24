# OSM 오프라인 지도 파이프라인

OSM 데이터셋 `fixtures/osm/`은 이전 합성 지도·주행과 별도 카탈로그를 사용한다. Android에는 같은 폴더를 asset으로 패키징하며 JVM CLI도 동일한 JSON을 읽는다. 앱은 선택 지역 하나만 로드한다. 지도 데이터 출처는 지도 안에 표시하고, 출처 버튼에서 스냅샷과 ODbL 라이선스 및 주소를 오프라인으로 볼 수 있다.

## 재현

Python 3.13에서 개발 도구 의존성을 설치한다. `acquire`만 원본 데이터 다운로드를 수행한다. 이후 명령은 로컬 PBF를 읽으며 일반 빌드, 앱과 CI에는 Python GIS 의존성 또는 PBF가 필요 없다.

```bash
python3.13 -m venv build/osm-venv
build/osm-venv/bin/pip install -r tools/osm/requirements.txt
build/osm-venv/bin/python -B tools/osm/import_maps.py acquire
build/osm-venv/bin/python -B tools/osm/import_maps.py import
build/osm-venv/bin/python -B tools/osm/generate_scenarios.py
python3 -B tools/osm/verify.py
./gradlew :core:simulation:run --args='fixtures/osm build/reports/osm.json build/reports/osm.md'
```

`--source`로 이미 확보한 PBF를 지정할 수 있다. 원본의 SHA-256과 PBF 헤더의 replication timestamp를 확인하며, 날짜가 이동하는 latest URL은 사용하지 않는다. `--output`으로 변환과 주행 생성을 대체 폴더에 수행해 기존 가공본과 비교할 수 있다. [원본 및 변환 manifest](../fixtures/osm/manifest.json) · [데이터 라이선스](../data/osm/README.md)

## 변환 계약

1. 함부르크 PBF에서 지역별 bbox에 걸친 원본 도로 way의 노드 참조를 보존한다. 경계를 따라 새 연결을 만들지 않는다. 외곽으로 나가는 완전한 way와 고정 경계를 별도로 보존하며, 주행 경로는 경계 안 65m 이상 여유가 있는 도로에서 선택한다.
2. 경위도를 (경도, 위도) 순으로 읽어 EPSG:32632에 투영하고, 지역 원점과의 차이를 1cm 단위 로컬 미터로 기록한다. 도로, 수역, 건물, 라벨, 경로와 관측을 같은 좌표계에서 처리한다.
3. 공유 OSM 노드, 제한 규칙의 via 노드, way 끝점에서 도로를 나눈다. 통행 방향과 일반 승용차 접근 조건, roundabout 기본 방향, 입체교차의 레벨과 교량·터널 속성을 보존한다. 단일 via-node 금지 및 의무 회전을 적용한다. 조건부 방향·접근 규칙과 지원하지 않는 via-way 회전은 보수적으로 제외한다. 명시적으로 위험물 차량에만 적용되는 조항은 일반 승용차 접근과 구분한다.
4. 시각 지형은 bbox에서 절단한다. multipolygon 관계의 외곽과 내부 링을 함께 조립한다. 도로 중심선 단순화는 적용하지 않는다. 표현 전용 건물과 지형에는 0.35/0.8m 단순화와 6/20㎡ 최소 면적을 적용한다. 삭제된 작은 형상과 유효하지 않은 장식은 manifest에 기록한다. 산책로는 별도 시각 레이어이며 인도·횡단보도 등 과밀한 소형 선은 제외한다.
5. 방향별 도로 ID는 way ID, 분할 시작 인덱스, F/R을 사용한다. 같은 물리 선의 양방향은 physicalId로 묶는다. 별도 차도 및 평행도로는 다른 physicalId를 갖는다. 앱 도로 외곽과 계획 경로는 실제로 재생하는 동일 좌표를 사용한다.
6. 개발 도구가 사전 주행 경로를 고른 뒤 200ms 간격의 위치·도로 ID·속도·방향을 계산한다. 이어서 1초 단위 가상 관측에 고정 시드의 노이즈와 장애를 넣는다. 런타임 그래프는 가상 실제 위치·장애 표식·기대값을 사용하지 않는다. 도로가 너무 짧아 200ms 표본 사이에 완전히 놓인 경우에도 이동은 그 에지를 따라간다.
7. 장시간 사용자용 18건과 별도 평가 10건을 분리한다. 평가용은 별도 시드, 주행 코스 선택 및 가능한 경우 장애 시각을 사용한다. 터널 손실은 실제 터널 시간대에서 고른다. 대표 회랑은 지상·교량·이탈 후 복귀를 사용하며, 터널 손실은 O03 전용 사례에서 재현한다.

OSM 전용 상한은 지도와 시나리오 합계 12MiB, 방향성 에지와 노드 각 12,000개, 도로 선분 40,000개, 다각형 30,000개, 표현 정점 260,000개다. 합성 fixture 상한은 기존 값을 사용한다. 지도 체크섬은 각 시나리오에 고정한다. 파생 데이터는 `fixtures/osm/`에 제공한다.


## 지역과 주행 규모

아래 도로 수는 양방향의 방향별 에지를 포함한다. 거리는 실제 가상 이동의 원본 에지 길이 합계다. 지역별 원본 선택·출력 SHA-256과 bbox는 [manifest](../fixtures/osm/manifest.json)에 있다.

| 지도 | 지역 | 지도 JSON | 방향성 에지 / 건물 | 사용자용 실제 경로 |
|---|---|---:|---:|---|
| O01 | Eimsbüttel | 3.00MiB | 4,226 / 8,149 | OL01 1.10km / 100초 · OL04 1.23km / 110초 |
| O02 | Moorfleet | 1.32MiB | 1,826 / 3,368 | OL02 2.40km / 100초 · OL11 2.02km / 120초 |
| O03 | A7 Tunnel Corridor | 5.15MiB | 7,505 / 13,118 | OL06 3.00km / 100초 · OL07 3.00km / 120초 |
| O04 | Barmbek | 3.63MiB | 5,634 / 9,573 | OL10 1.04km / 100초 · OL12 1.18km / 100초 |
| O05 | Hamburg-Nordwest | 6.05MiB | 6,495 / 23,266 | OL13 1.65km / 120초 |
| O06 | Elbbrücken | 3.34MiB | 5,451 / 6,931 | OL08 1.51km / 110초 · OL17 1.57km / 100초 |
| O07 | Volksdorf | 1.47MiB | 2,020 / 5,753 | OL03 0.79km / 100초 · OL14 0.65km / 90초 |
| O08 | Blankenese | 0.71MiB | 683 / 2,881 | OL09 1.06km / 110초 |
| O09 | Wandsbek | 3.47MiB | 4,963 / 11,475 | OL05 1.40km / 100초 · OL15 1.35km / 110초 |
| O10 | Harburg | 4.93MiB | 6,520 / 16,476 | OL16 1.77km / 120초 · ODEMO_LONG 2.79km / 180초 |
