# OSM 오프라인 화면 확인

API 36 에뮬레이터를 비행기 모드로 두고 Wi-Fi를 끈 상태에서 Debug APK를 실행했다. 해상도는 1080×2400이다. 지도 10개의 Follow와 Overview를 실제 실행해 캡처하고, 캡처에서 도로·지형·경로와 화면 유지를 확인했다. O01과 O10은 야간에서도 캡처했다. 지도 오른쪽 위의 출처는 카메라 변경과 함께 움직이지 않는다. 출처 버튼을 눌러 라이선스 정보가 화면에 나오는 것도 계측했다.

첫 O01 캡처에서는 인도·횡단보도 점선이 차량 도로 주변에 밀집했다. 표현 대상에서 해당 인도·횡단보도 유형을 제외하고 공원과 별도 보행로를 유지했다. 터널 범례는 OSM 주행 계획에 터널이 있을 때 표시한다. [O01 조정 전](demo/osm/O01-before.png) · [O01 조정 후](demo/osm/O01-follow.png)

| 지역 | 실제 캡처 | 관찰 |
|---|---|---|
| O01 Eimsbüttel | [Follow](demo/osm/O01-follow.png) · [Overview](demo/osm/O01-overview.png) | 도심 블록, 불규칙한 건물 외곽, 공원과 연결 교차로 |
| O02 Moorfleet | [Follow](demo/osm/O02-follow.png) · [Overview](demo/osm/O02-overview.png) | 실제 고속화도로 본선과 굽은 진출입 램프 |
| O03 A7 터널 회랑 | [Follow](demo/osm/O03-follow.png) · [Overview](demo/osm/O03-overview.png) | 수역과 점선 터널, 신호 손실 중 추정 마커 |
| O04 Barmbek | [Follow](demo/osm/O04-follow.png) · [Overview](demo/osm/O04-overview.png) | 간선과 인접 도로, 여러 도시 블록 |
| O05 Hamburg-Nordwest | [Follow](demo/osm/O05-follow.png) · [Overview](demo/osm/O05-overview.png) | 입체적으로 교차하는 A7과 연결 램프 |
| O06 Elbbrücken | [Follow](demo/osm/O06-follow.png) · [Overview](demo/osm/O06-overview.png) | 강을 건너는 교량과 양안 접근 도로 |
| O07 Volksdorf | [Follow](demo/osm/O07-follow.png) · [Overview](demo/osm/O07-overview.png) | 교외 생활도로, 녹지와 곡선 주행 |
| O08 Blankenese | [Follow](demo/osm/O08-follow.png) · [Overview](demo/osm/O08-overview.png) | 굽은 도로, 수변과 경사 지역의 블록 |
| O09 Wandsbek | [Follow](demo/osm/O09-follow.png) · [Overview](demo/osm/O09-overview.png) | 교차로와 회전이 이어지는 교통 회랑 |
| O10 Harburg | [Follow](demo/osm/O10-follow.png) · [Overview](demo/osm/O10-overview.png) | 도시 지구, 교량·수역, 여러 분기와 합류 |

[O01 야간](demo/osm/O01-night.png) · [O10 야간](demo/osm/O10-night.png)

스크린샷은 위 장면의 대표 시각을 기록한다. 이탈과 복귀의 기대 이벤트는 [JVM 독립 평가](osm-evaluation-report.md)에서 확인했다. 픽셀 자동 비교, 글꼴 확대 범위 전수 검사 및 이번 개정의 물리 단말 렌더링 프레임 측정은 실행하지 않았다.
