# 데모 재현

기본 [OSM 주행과 실제 캡처](osm-visual-verification.md)는 ODEMO_LONG에서 확인할 수 있다. 아래 시간표를 재현하려면 시나리오 선택에서 합성 **DEMO_LONG**을 고른 뒤 재생을 누른다. 1×에서 3분, 5×에서 약 36초가 걸린다. 판정 경계를 정밀하게 확인하려면 일시정지하고 한 단계씩 실행한다.

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

## 지도 품질 개정 캡처

[10개 지도의 최신 Follow·Overview와 전후 비교](map-visual-verification.md)

## 초기 확장판의 API 36 에뮬레이터 캡처

모든 지도는 아래 시나리오를 실제 앱에서 선택한 뒤 첫 예정 시각을 처리하고 Overview로 전환하여 촬영했다.

| 지도 | 시나리오 | 캡처 |
|---|---|---|
| M01 도심 격자 | L01 | [전체 경로](demo/extended/M01.png) |
| M02 고속화도로 | L02 | [전체 경로](demo/extended/M02.png) |
| M03 터널 회랑 | L07 | [전체 경로](demo/extended/M03.png) |
| M04 평행도로 | L10 | [전체 경로](demo/extended/M04.png) |
| M05 입체 교차로 | L13 | [전체 경로](demo/extended/M05.png) |
| M06 수변·교량 | L08 | [전체 경로](demo/extended/M06.png) |
| M07 교외 생활도로 | L03 | [전체 경로](demo/extended/M07.png) |
| M08 산지 곡선 | L09 | [전체 경로](demo/extended/M08.png) |
| M09 복합 교통 회랑 | L15 | [전체 경로](demo/extended/M09.png) |
| M10 복합 장거리 | DEMO_LONG | [전체 경로](demo/extended/M10.png) |

DEMO_LONG 1×의 [87초 Follow·DR](demo/extended/long-follow-dr.png), [96초 Follow·마지막 확인 위치](demo/extended/long-follow-limit.png), [완료 결과 패널](demo/extended/long-results.png). 빠른 회귀 S07의 [DR 중단 화면](demo/extended/dr-limit.png)도 확인할 수 있다. 기존 [15초 DEMO 시작](demo/ready.png)과 [결과](demo/results.png)는 v1 화면 기록이다.
