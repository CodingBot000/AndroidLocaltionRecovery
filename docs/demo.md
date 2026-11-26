# 실행 가이드 및 데모

[README](../README.md) · [지도 전체 화면](osm-visual-verification.md) · [Live 위치](live-location.md)

## 기본 OSM 데모

기본 ODEMO_LONG(서울 성수동, 가상 180초)을 실행한다. GPS 점프·신호 손실·재획득·경로 이탈과 복귀를 지도 및 진단 로그에서 확인한다.

| 시나리오 | 관찰 내용 |
|---|---|
| ODEMO_LONG | 복합 위치 장애와 이탈·복귀 |
| OL06 / OL07 | 남산 터널의 짧은·긴 손실 · [OL07 신호 손실 중 위치 추정 화면](demo/osm/O03-follow.png) |
| OL08 | 수영강 교량 위 정차·손실 |
| OL13 | 수서 나들목 가상 이탈·복귀 |

<p align="center">
  <a href="demo/refactor/O10-mid.png"><img src="demo/refactor/O10-mid.png" width="210" alt="성수동 O10 Mid"></a>
  <a href="demo/refactor/O07-follow.png"><img src="demo/refactor/O07-follow.png" width="210" alt="운중동 O07 Follow"></a>
</p>

<p align="center">성수동 O10 · Mid &nbsp; | &nbsp; 운중동 O07 · Follow</p>

Follow, Overview, Free 및 재생·일시정지·Step·Reset·배속 조작을 지원한다. [전체 캡처](osm-visual-verification.md)

## 합성 DEMO_LONG 재현 (기존 회귀)

시나리오 선택에서 합성 **DEMO_LONG**을 고른 뒤 재생을 누른다. 1×에서 3분, 5×에서 약 36초가 걸린다. 판정 경계를 정밀하게 확인하려면 일시정지하고 한 단계씩 실행한다.

| 가상 시각 | 화면에서 확인할 내용 |
|---:|---|
| 0–30초 | 도심 구간 가감속·정차·회전 |
| 32–49초 | 좌표 흔들림, 39초 위치 점프 거부, 저품질 관측 |
| 55–80초 | 고속화도로 주행 |
| 84–96초 | 신호 손실 및 DR, 96초 추정 한도 |
| 106–109초 | 첫 불량 관측 거부, 연속 정상 관측으로 재획득 |
| 131–157초 | 계획 외 분기에서 경로 이탈 권고 |
| 157–180초 | 계획 경로 재합류·복귀 판정, 주행 종료 |

카메라 **전체 경로**는 계획 경로 전체를 맞춘다. 확대·축소 또는 지도 끌기는 수동 탐색으로 전환하고 **차량 추적**은 채택·추정 위치로 돌아온다. 지도 테마와 가상 실제 위치 표시는 재생 중에도 바꿀 수 있다. 판정과 요약은 **진단 보기** 및 주행 종료 후 **결과**에서 확인한다.

[장시간 합성 주행 정량 평가](extended-evaluation-report.md) · [합성 지도와 시나리오 설계 이력](map-and-scenarios.md)
