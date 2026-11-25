# 국내 OSM 초기 렌더링 성능 비교 이력

[후속 성능 비교](refactor-performance.md) · [최신 플랫폼 성능](verification.md#성능-비교와-자원)

국내 OSM 전환 단계의 에뮬레이터 단회 비교다. 후속 리팩터링 및 플랫폼 측정과 기준선이 다르다.

사용자 요청에 따라 API 36 ARM64 에뮬레이터의 1080×2400 세로 환경에서 간략하게 측정했다. Macrobenchmark는 동일 APK·성수동 데이터·ODEMO_LONG에서 전체 목록을 순회하고 원본 Path만 쓰는 모드와 공간 인덱스·표현 단순화·레이어 분리를 적용한 모드를 비교했다. 콜드 실행 후 1×로 재생하며 약 4초에 두 번 축소하고, 약 8초에 Overview로 바꿔 12초까지 진행했다. 두 모드 모두 `CompilationMode.None()`으로 한 차례 측정했다.

| 지표 | 전체 탐색 | 공간 인덱스 |
|---|---:|---:|
| 첫 프레임 TTID | 417.9ms | 566.7ms |
| 지도 준비 TTFD | 967.3ms | 1,401.8ms |
| 프레임 CPU 시간 P50 | 240.2ms | 145.7ms |
| 프레임 CPU 시간 P95 | 384.9ms | 373.6ms |
| 프레임 시한 초과 P50 | 250.0ms | 157.3ms |
| 프레임 시한 초과 P95 | 545.7ms | 535.9ms |
| 프레임 표본 | 88건 | 87건 |
| 지도 준비 직후 프로세스 PSS | 93,683KiB | 88,455KiB |

이 표본에서 프레임 CPU 중앙값은 낮았고 지도 준비 시간은 더 길었다. PSS는 두 모드에서 각각 별도로 콜드 실행한 뒤 정지된 기본 지도 화면에서 한 번씩 읽었다. 프레임 지표에는 콜드 화면과 카메라 조작이 포함된다. 에뮬레이터의 1회 비교라서 반복성이나 실기기 성능을 판단하기에는 표본이 부족하다. [Android Macrobenchmark 지표](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)

APK 크기는 전환 전 함부르크와 전환 후 대한민국 데이터를 각각 포함한 파일에서 비교했다. 데이터 범위의 차이가 포함된다.

| APK | 함부르크 기준선 | 국내 상세 지도 |
|---|---:|---:|
| Debug | 22,519,586B (21.48MiB) | 21,026,430B (20.05MiB) |
| 서명하지 않은 Release | 16,714,104B (15.94MiB) | 15,362,720B (14.65MiB) |

[Macrobenchmark 원본](benchmarks/korea-api36-macrobenchmark.json) · [APK 크기와 메모리 원본](benchmarks/korea-api36-size-memory.json)
