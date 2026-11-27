# PR #9 G3 에뮬레이터 주행 최종 재시험

## 범위와 원본 계측

2026-11-27 KST에 기준 A `a732af2dddfb86514fbfa76a6d7971f31e8d23da`와 최종 후보 C `ce617de21ffbf5485564c9d7bc589a3b6085c02c`를 비교했다. 시작 PR head는 `cceeea69c6eaa228050d7c075949630525985b81`이었다. 이번 실행은 G3의 O10 주행만 다룬다. 기존 에뮬레이터 콜드 기동·지도 전환과 실기기 G7·G8·G9 기록을 유지했다. [이전 에뮬레이터 측정](recovery-ui-switch-rca.md#f4--최종-제품-성능)

이전과 같은 `LocationRecoveryComposeRCA36`의 API 36 ARM64·SwiftShader를 사용했다. 화면 1,080×2,400·density 420·60Hz, 앱 `ko-KR`, 글꼴 1.0, 세 애니메이션 배율 0, 비행기 모드 OFF·Wi-Fi ON, 에뮬레이션 AC 전원·배터리 100%·25°C·thermal status 0을 확인했다. 기존 AVD에서 한 대만 띄우고 동일한 네 세트 동안 유지했다. 에뮬레이터는 버전 37.1.11.0이고 게스트는 4 vCPU 및 실제 할당 RAM 2,560MiB였다.

이전 원본 비교의 앱·공용 계측 APK를 재사용하고 각 세트 설치 후 SHA-256을 검증했다. A와 C의 원본 `RefactorMapBenchmark#rideO10`은 동일하며 `CompilationMode.None()`과 기존 `EMULATOR` 경고 허용을 유지했다. 준비된 O10에서 1×로 재생한 뒤 가상 3초에 축소(×0.8)와 Overview, 6초에 Follow 복귀, 8초까지 주행한다. A/C에서 화면 폭 기준 480m의 초기 Follow와 수동 축척을 유지하는 복귀 구현이 동일하다. 이전 계측의 실제 캔버스는 둘 다 1,016×1,215px였다. [기존 카메라 확인](recovery-ui-switch-rca.md#f4--최종-제품-성능)

| APK | SHA-256 |
|---|---|
| 기준 A | `fb1fdc7d464cf47c8cb0011a9d2a659dfd43eb2de2b40bdc077d2fb16d8f4f76` |
| 후보 C | `fa73ac812fb4e28d52a9974bd31dab9ac9c5d15136df08da0d945c109e1ad474` |
| 공용 원본 계측 | `0ee2174794b53a278c4f99131f4a3fa39b58d65a9011a07a144f9cff2544e4f2` |

## 집계와 유효성 기준

A9→C9→C10→A10 순서로 세트당 3회, 빌드별 6회만 실행했다. AndroidX 원본 Frame CPU·Overrun 표본을 빌드별로 모아 nearest-rank P95를 구하고, 세트와 개별 반복의 P95·표본 수·전체 분포도 보존한다. 원본 Overrun P95에서 C가 **10% 이상 또는 5ms 이상** 증가하면 기존 조사 기준에 해당한다. UI·RenderThread의 `Running`과 `R/R+`는 별도로 집계한다. 원본 FrameTimingMetric 표본과 FrameTimeline에서 일대일로 연결한 surface token의 수 및 `App Deadline Missed` 태그는 별도로 센다. [AndroidX FrameTimingMetric](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/FrameTimingMetric) · [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)

세트 시작 전 호스트 CPU 유휴율·메모리 압박·백그라운드 작업을 확인했다. 실행 중에는 약 4초 간격으로 같은 호스트 지표를 수집했다. 호스트의 Gradle·Kotlin 빌드 프로세스, Perfetto 분석과 타 에뮬레이터가 겹치는지 점검했다. 기존 원본 trace의 게스트 수집은 벤치마크 계약에 포함된다. 원본 테스트 실패·데이터 손상 및 명시한 환경 계약 위반을 별도 기록하는 방식이며 프레임 값의 크기를 제외 조건으로 사용하지 않는다.

## 최종 원본 결과

세트 4개와 원본 trace 12개가 모두 유효했다. 어떤 세트나 프레임도 제외하지 않았다.

| 실행 순서 | 반복별 프레임 수 | Frame CPU P95 (ms) | Frame Overrun P95 (ms) |
|---|---:|---:|---:|
| A9 | 50 / 50 / 50 | 372.6 | 517.3 |
| C9 | 57 / 52 / 51 | 369.6 | 525.2 |
| C10 | 52 / 51 / 52 | 361.0 | 516.8 |
| A10 | 51 / 54 / 50 | 363.4 | 520.9 |
| **A 합계** | **305** | **365.85** | **520.49** |
| **C 합계** | **315** | **365.83** | **521.45** |

원본 Overrun P95는 **+0.96ms(+0.18%)**로 두 조사 기준 이내였다. A9→C9의 Overrun P95는 +7.91ms(+1.53%)로 절대 기준을 넘었다. 역순의 C10→A10에서는 후보가 4.04ms(0.77%) 낮았다. 두 인접 비교에서 같은 방향의 악화는 나타나지 않았다.

| 원본 프레임 분포 (ms) | A 최소 / P50 / P90 / P99 / 최대 | C 최소 / P50 / P90 / P99 / 최대 |
|---|---:|---:|
| Frame CPU | 6.6 / 182.9 / 362.3 / 421.0 / 520.7 | 7.0 / 237.2 / 361.0 / 373.5 / 465.0 |
| Frame Overrun | −8.6 / 199.8 / 514.1 / 567.8 / 689.6 | −9.3 / 238.2 / 514.8 / 535.6 / 695.4 |

후보의 중앙 프레임은 두 지표에서 더 느렸고, P90과 P95는 가까웠다. P99는 후보에서 낮았다. 최대 표본을 포함한 원본 프레임 620개와 순서·반복별 P95를 공개 집계에 보존했다. [최종 원본 프레임 데이터](benchmarks/recovery-ui-pr9-g3-final-ride-api36.json)

## 스레드 실행과 deadline

| 세트별 중앙값 (ms) 또는 surface token 수 | A9 | C9 | C10 | A10 |
|---|---:|---:|---:|---:|
| UI 실행 / runnable | 386.0 / 29.8 | 415.0 / 33.2 | 396.0 / 27.8 | 388.3 / 34.2 |
| RenderThread 실행 / runnable | 5,326.8 / 37.1 | 6,111.4 / 22.9 | 5,893.3 / 26.9 | 5,814.9 / 43.0 |
| App Deadline Missed 태그 / 실제 surface token | 112 / 151 | 130 / 161 | 127 / 155 | 119 / 155 |

세트당 3개 trace의 `measureBlock`에서 세었다. 빌드별 6회에서 UI 실행 중앙값은 387.0→402.3ms, runnable은 31.8→30.5ms였다. RenderThread 실행 중앙값은 5,427.6→6,002.3ms, runnable은 40.1→24.9ms였다. 반복별 블록 길이로 나눈 RenderThread 실행 비율의 중앙값은 66.8%→70.7%였다. 실행 시간이 길어진 사실과 스레드가 실행을 기다린 시간을 구분해 기록했다. [Perfetto 스케줄링 이벤트](https://perfetto.dev/docs/data-sources/cpu-scheduling)

앱 surface token 306→316개를 모두 일대일로 연결했다. `App Deadline Missed` 태그는 231/306(75.5%)→257/316(81.3%)였고 Actual 종료가 Expected 종료보다 늦은 token은 236→259개였다. 이 토큰 수는 AndroidX가 집계한 원본 프레임 표본 수와 별개다. 후보에서 더 큰 RenderThread 실행량, 중앙 프레임 시간과 deadline 태그 비율은 관측값으로 남긴다. 이 근거로 특정 제품 변경을 원인으로 지목할 수 없다.

## 호스트 편차와 독립 비교

호스트는 macOS 26.5.1의 16코어·64GiB Mac이었다. 각 세트 직전 마지막 2초 창의 CPU 유휴율은 89.4–92.7%였고, 계측 중 33개 샘플의 범위는 24.0–63.7%, 1분 load average는 3.90–10.09였다. 모든 샘플에서 메모리 압박은 정상 수준이었고 세트 전후 swap 사용량은 1,272.62MiB였다. 에뮬레이터가 가장 큰 CPU 사용 프로세스였고 데스크톱 앱의 사용량도 세트별로 달랐다. Gradle·Kotlin 빌드, 호스트 Perfetto 분석, 다른 Android 에뮬레이터는 관측되지 않았다. 게스트 기록을 로컬에서 분석할 때는 에뮬레이터를 이미 종료한 상태였다.

12개 trace를 호스트 관측 창에 맞춰 비교했다. 반복별 Overrun P95와 RenderThread 실행 시간의 Pearson r은 0.65, 호스트 유휴율과의 r은 −0.08이었다. 4초 간격의 호스트 평균과 12회 관측으로 개별 프레임의 호스트 스케줄링 비용이나 이전 재시험의 원인을 확정할 수 없다. [호스트 표본과 trace별 집계](benchmarks/recovery-ui-pr9-g3-final-ride-api36.json)

| 독립 주행 비교 | A → C Frame CPU P95 (ms) | A → C Overrun P95 (ms) | Overrun 차이 (ms) | A / C 프레임 |
|---|---:|---:|---:|---:|
| 에뮬레이터 이전 본 측정 | 446.1 → 459.2 | 600.37 → 631.22 | +30.85 | 431 / 438 |
| 에뮬레이터 이전 주행 재시험 | 471.3 → 687.5 | 673.83 → 1,023.31 | +349.48 | 312 / 310 |
| 에뮬레이터 최종 A→C→C→A | 365.85 → 365.83 | 520.49 → 521.45 | +0.96 | 305 / 315 |
| SM-G975N의 기존 실기기 주행 | 102.6 → 103.3 | 101.42 → 102.01 | +0.58 | 92 / 92 |

이전 주행 재시험의 후보 세트별 Overrun P95는 1,287.8ms와 550.7ms였다. 기존 C6 제외는 Gradle 평가와 로컬 trace 분석이 겹친 경우로 원본과 제외 사유를 유지했다. 이번 재시험은 네 세트를 모두 포함했다. 에뮬레이터와 실기기는 각 환경 안에서만 집계했다. [이전 에뮬레이터](benchmarks/recovery-ui-pr9-final-api36.json) · [실기기 원본](benchmarks/sm-g975n-pr9-g7.json)

## 최종 판정과 보존

**G3 최종 판정은 PASS다.** 기존 에뮬레이터 콜드 기동·지도 전환 근거와 최종 주행 6회씩의 원본 P95에 적용한 판정이다. 이전의 큰 후보 주행 P95 악화는 통제된 최종 비교에서 재현되지 않았다. 단일 인접 비교의 +7.91ms, 후보 중앙 프레임·deadline 태그 비율 증가와 이전 편차의 원인은 기록에 남겼다. 제품 결함을 특정할 증거는 확인되지 않았다. 이 시험으로 G3 계측을 종료했다. G7·G8·G9는 기존 INCONCLUSIVE이며 G8 잔여 위험을 유지했다. [통합 검증 게이트](recovery-ui-validation.md#f7--기존-pr과-수용-게이트)

기존 AVD의 두 APK, 계측 미디어 5개와 설정을 확인한 뒤 AVD를 종료했다. 원본 로그, 소스별 APK, 환경, trace 12개와 결과 JSON 4개는 로컬 `build/reports/recovery-ui/pr9-g3-final-20261127/`에 보존했다. [최종 익명화 데이터와 해시](benchmarks/recovery-ui-pr9-g3-final-ride-api36.json)
