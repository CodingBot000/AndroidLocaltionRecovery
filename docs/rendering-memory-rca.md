# OSM 렌더링과 반복 재생 메모리 조사

## 기준과 실험 환경

- 기준 A는 `a067a970bbe0e69797fb8eb319e78ae22bd01f94`다. 원격 `main`과 PR #4의 두 CI 작업을 확인했다. 기존 사용자 작업 트리에서 별도 브랜치와 기준선 worktree를 만들었다. [기준 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45641629312)
- SM-G975N, Exynos 9820, Android 12 API 31, ARM64, 1080×2280 화면 오버라이드, density 420, 약 60Hz에서 USB를 연결했다. A의 각 세트 시작과 종료 시 thermal status는 0이었다. 배터리 온도는 25.7–30.1°C였고, 애니메이션 스케일과 화면 설정은 유지했다. 열 상태 0에서도 클럭과 백그라운드 부하는 달라질 수 있다.
- 렌더링은 JDK 17, SDK 36, Compose BOM 2025.08.00(UI 1.9.0), Kotlin 2.2.21, AndroidX Benchmark 1.5.0으로 빌드했다. 대상 APK는 Release 상속 `benchmark` 변형으로 `profileable`이며 benchmark 모드는 `CompilationMode.None()`이다. 준비 신호, O10→O07→O10 및 O10 1× 주행·Zoom out·Overview·Follow는 기존 `RefactorMapBenchmark`를 실행했다. A의 제품 소스와 원본 workload에 별도 어댑터가 필요하지 않았다.
- 과거 기준 H는 `653e311`에 Benchmark 패키지에서만 동작하는 `MainActivity.onNewIntent` 시나리오 선택 어댑터를 추가한 빌드다. 이전 실기기 보고서의 원본 trace, frame sample 및 메모리 로그를 로컬에서 확인했다. 당시 경로에 남아 있는 APK 상당수는 기록된 SHA-256과 달라 과거 측정에는 당시의 원본 자료를 사용했다. 현재 A의 APK 6개는 로컬 보고 경로에 별도로 복사하고 해시를 확인했다. [과거 실기기 보고서](real-device-final-validation.md)
- 이전 H와 최신 A의 Follow 복귀 동작에는 차이가 있다. `83a5e7f` 이후에는 사용자가 선택한 축척을 복원하므로, 같은 버튼을 눌러도 6초 이후 지도 범위가 달라질 수 있다. 후보는 A의 카메라 계약에 맞춰 평가한다.
- 이번 시험 동안 사용자 승인에 따라 30분 오프라인 계측에서만 비행기 모드 ON, Wi‑Fi OFF로 전환한다. 시험 전 설정을 저장하고 성공·실패 시 복원한다. 충전 및 온도는 조건 기록에 사용한다. 배터리 A/B는 **NOT_RUN**이다.

## 실기기 렌더링 기준선

| 지표 | 과거 H | 과거 d0b3822 | 이번 A |
|---|---:|---:|---:|
| O10 콜드 TTID 중앙값 | 545.1ms | 464.3ms | 586.6ms |
| O10 콜드 TTFD 중앙값 | 1,871.8ms | 1,343.9ms | 1,388.2ms |
| O10→O07 지도 준비 중앙값 | 661.9ms | 670.2ms | 669.6ms |
| 지도 전환 Frame CPU P95 | 61.6ms | 91.0ms | 92.5ms |
| 지도 전환 Frame Overrun P95 | 47.9ms | 81.3ms | 79.2ms |
| O10 주행 Frame CPU P95 | 86.0ms | 101.8ms | 83.9ms |
| O10 주행 Frame Overrun P95 | 78.8ms | 102.1ms | 68.5ms |

각 빌드와 시나리오에서 3회씩 3세트를 실행했다. Frame P95는 AndroidX 원시 프레임을 합쳐 nearest-rank로 집계했다. 전환의 CPU 및 overrun 표본은 H 48개, d0b3822 117개, A 102개다. 주행은 각각 113개, 92개, 89개다. Startup과 지도 준비 값은 반복 9개의 중앙값이다.

지도 준비 시간은 과거와 비슷했고 전환의 느린 프레임은 다시 관측됐다. 주행 P95는 과거 d0b3822의 상승을 이번 A에서 재현하지 못했다. A의 주행 Overrun P99는 100.4ms로, 일부 프레임의 지연은 남았고 89개 원시 표본에 모두 양수 overrun이 있었다. TTID 변화에는 실행 간 환경 변동도 포함될 수 있다. d0b3822와 A를 비교한 생산 소스 범위에서 앱·지도 구현 변경은 없었다.

AndroidX 지표에 포함된 원시 표본과 FrameTimeline의 앱 surface token은 별개의 집계다. 앱 프로세스와 MainActivity surface에서 토큰과 expected 행을 짝지었고, SurfaceFlinger는 display token으로 연결했다. UI와 RenderThread의 sched 구간은 `measureBlock`과의 교집합을 합산했다. 종료 시각 정보가 빠진 actual 행을 시간 분포에 보간하는 방법은 사용하지 않았다. 한 trace에는 여러 계층의 slice가 중첩될 수 있어 함수별 벽시계 시간을 중복 합산하지 않는다. [Macrobenchmark 프레임 지표](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) · [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)

| 전환·주행 trace 집계 | H | A |
|---|---:|---:|
| 전환 UI 실행 시간 중앙값 | 310.1ms | 402.6ms |
| 전환 RenderThread 실행 시간 중앙값 | 167.0ms | 187.4ms |
| 전환 JIT 스레드 실행 시간 중앙값 | 14.3ms | 204.4ms |
| 주행 UI 실행 시간 중앙값 | 1,401.4ms | 1,381.7ms |
| 주행 RenderThread 실행 시간 중앙값 | 3,061.3ms | 3,238.7ms |
| 전환 앱 Deadline Missed / 앱 surface token | 28 / 60 | 50 / 119 |
| 주행 앱 Deadline Missed / 앱 surface token | 639 / 754 | 597 / 709 |

주행의 여러 trace를 보면, 이전에 대표 trace 하나에서 관측한 UI CPU 증가가 중앙값에서는 유지되지 않았다. 전환은 UI 스레드와 JIT가 각각 더 오래 실행됐다. A에서 전환의 긴 프레임 세 개를 연결했을 때 `doFrame`은 48.9–57.0ms, `DrawFrames`는 67.1–81.3ms였다. 세 프레임이 모두 App Deadline Missed였고 연결된 SurfaceFlinger 분류는 `None`이었다. 두 slice가 겹칠 수 있어 합을 프레임 시간으로 해석하지 않는다. SurfaceFlinger 분류는 수집된 행의 범위에서 읽는다.

같은 전환을 별도 coarse trace 빌드에서 세 번 실행했다. 반복마다 O07과 O10의 월드 준비가 각각 한 번씩 발생했고, worker의 두 준비 구간 합은 118.4–140.7ms였다. UI의 두 정적 지도 기록은 21.2–24.0ms, 두 라벨 그리기는 6.6–7.3ms, 두 오버레이는 0.19–0.21ms였다. 가시 토지·도로 조회 합은 1.25–1.68ms였다. 이는 측정 지점을 좁히는 진단이며 worker·UI·RenderThread의 시간을 더해 전환 지연을 설명할 수는 없다. 첫 진단 시도는 USB 연결이 끊겼고 재연결 후의 세 반복을 따로 보존했다.

## 후보의 변경 범위와 화면 검증

후보는 최적화된 하드웨어 렌더러에서 서로 다른 두 점만 가진 도로의 외곽선과 채움을 Canvas 선분으로 기록한다. 기존 도로 순서, 폭, 색상, 둥근 선 끝, 카메라, 경로와 라벨을 유지한다. 두 점이 같은 경우, 여러 점을 가진 도로, 터널·고속도로 점선과 API 26 소프트웨어 fallback은 기존 Path 경로를 통과한다. 최신 코드에서 주행의 RenderThread 실행 비중이 크고 입력 O10의 18,490개 edge 중 10,938개가 두 점으로 구성되어 이 변수를 골랐다. 그 차이가 렌더링과 메모리에 주는 효과는 각각의 실기기 A/B에서 검증한다.

API 36 ARM64 에뮬레이터에서 기준선과 후보의 Release 상속 APK를 따로 설치했다. O01, O07, O10에서 동일한 일시정지 재생 시각·카메라 상태를 확인하고 Follow, 확대/축소, Overview, 야간 지도 12개 영역을 비교했다. 10개 영역은 RGB가 일치했다. O10 Follow에서 3픽셀, 축소한 O10에서 1픽셀이 달랐다. 변경된 4픽셀은 모두 기준 이미지의 색상 경계에 있었고, 균일한 내부 영역 변화는 0픽셀이었다. 앱과 Live에 패키징된 fixture도 모두 바이트 단위로 일치했다.

## 실기기 렌더링 A/B

A의 완료된 3세트를 그대로 사용했다. B는 `e9e406d`의 후보를 적용한 APK로 같은 SM-G975N에서 시나리오별 9회 실행했다. 화면 1080×2280, density 420, 시스템 애니메이션, 밝기, USB 연결, 네트워크, 계측 APK와 `CompilationMode.None()`을 맞췄다. B의 시작·종료 열 상태는 모두 0이고 온도는 27.9–31.4°C였다. 두 빌드의 세트를 순차 측정해 측정 순서와 온도 차이의 영향이 남는다. [실험 패치](benchmarks/two-point-road-strokes.patch) · [익명화 집계](benchmarks/sm-g975n-render-memory-optimization.json)

| 지표 | A | B | 변화 |
|---|---:|---:|---:|
| O10 콜드 TTID 중앙값 | 586.6ms | 616.6ms | +5.1% |
| O10 콜드 TTFD 중앙값 | 1,388.2ms | 1,808.9ms | +30.3% |
| O10→O07 지도 준비 중앙값 | 669.6ms | 657.2ms | −1.8% |
| 전환 Frame CPU P95 | 92.5ms | 62.0ms | −33.0% |
| 전환 Frame Overrun P95 | 79.2ms | 73.5ms | −7.2% |
| 주행 Frame CPU P95 | 83.9ms | 85.8ms | +2.3% |
| 주행 Frame Overrun P95 | 68.5ms | 85.0ms | +24.2% |

Frame P95는 프레임 원시 표본을 합산한 nearest-rank 값이다. 전환 표본은 A 102개·B 93개, 주행은 A 89개·B 116개였다. 전환의 반복별 Overrun P95 중앙값은 79.1→48.0ms였다. 합산 P95 개선은 목표 20%에 미달했다. A/B 각각 18개의 프레임 trace에서 expected/actual 연결을 확인했다. 전환의 App Deadline Missed는 50/119→32/103, 주행은 597/709→592/713이었다. AndroidX overrun과 FrameTimeline 앱 토큰은 집계 대상과 정의가 다르다.

전환의 UI 스레드 실행 시간 중앙값은 402.6→179.1ms였고, 주행은 1,381.7→1,370.4ms였다. 주행 RenderThread는 3,238.7→3,295.0ms였다. 후보는 전환 프레임 일부의 비용을 줄이는 효과와 함께 시작 및 주행 지연을 관측했으므로 채택 기준을 충족하지 못했다.

## 실기기 기준선 메모리

SM-G975N의 Debug 빌드에서 O10을 처음부터 1×로 10회 재생했다. 같은 프로세스에서 실제 주행 시간이 총 1,801.213초였다. 마지막 초기화 120초 뒤까지 표본 62개를 확보했고, O01·O10 전환과 재생 20회 및 Activity 재생성도 통과했다. 온도는 26.8–27.9°C였고 열 상태는 17회 관측에서 모두 0이었다. 앞서 4회까지만 진행한 실행을 별도 보존했다. 이번 10회 시험 뒤 비행기 모드 OFF와 Wi‑Fi ON 복원을 확인했다.

| 관측 지점 | 전체 PSS | 네이티브 힙 PSS | Java 사용 힙 | Graphics |
|---|---:|---:|---:|---:|
| 1회 준비 | 227,220KiB | 31,940KiB | 50,017KiB | 47,307KiB |
| 1회 완료 | 393,791KiB | 86,768KiB | 62,439KiB | 144,563KiB |
| 10회 완료 | 415,171KiB | 107,402KiB | 52,209KiB | 153,703KiB |
| 10회 초기화 | 417,879KiB | 107,402KiB | 55,241KiB | 153,703KiB |
| 10회 초기화 후 120초 | 389,423KiB | 109,902KiB | 55,273KiB | 121,587KiB |

전체 PSS 최대 관측치는 425,001KiB다. 2–10회 완료 시점의 전체 PSS 중앙값은 408,171KiB였고, Theil–Sen 기울기는 회차당 2,622.25KiB였다. 마지막 초기화에서 유휴 120초까지 PSS가 28,456KiB 줄었다. 마지막 표본에서 네이티브 할당 API가 0으로 보고한 값은 측정 불가로 남겼으며 네이티브 힙 PSS는 별도로 관측했다.

## 메모리 진단

API 33 에뮬레이터의 별도 Debug ART 참조 그래프에서 A의 접근 가능한 Compose `AndroidPath` 래퍼는 첫 준비 804개, 한 번의 180초 재생 완료 2,230개, 초기화 후 2,209개였다. 세 시점에서 `MapWorld`는 1개였다. Activity 종료 직후와 약 59초 뒤의 유효한 그래프에서 `MapWorld`, `MainActivity`, `AndroidPath`가 모두 도달 불가능했다. 초기화 뒤 활성 월드가 Path를 재사용하고 Activity 종료 시 해당 그래프가 해제되는 경로를 이 시나리오에서 확인했다. 첫 종료 시점의 미완성 그래프와 버퍼가 넘친 첫 네이티브 표본은 근거에서 제외했다.

같은 에뮬레이터의 후보는 첫 준비 678개, 첫 재생 완료 1,778개, 초기화 후 1,757개의 `AndroidPath` 래퍼를 관측했다. 재생 완료 및 초기화 시 각각 A보다 452개 적었다. 후보도 활성 `MapWorld` 1개와 종료 후 세 클래스의 도달 가능한 인스턴스 0개를 확인했다. 이 차이는 두 점 도로를 Path 없이 기록하는 경로와 일치한다. 실기기의 전체 PSS와 네이티브 메모리는 별도로 평가했다.

## 측정과 진단 재실행

후보 APK를 다시 만들 때는 기준 SHA에서 격리 worktree를 만들고 실험 패치를 적용한다.

```bash
git worktree add --detach ../render-memory-trial a067a97
git -C ../render-memory-trial apply "$PWD/docs/benchmarks/two-point-road-strokes.patch"
```

```bash
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :live:assembleDebug :live:assembleDebugAndroidTest
adb -d shell am instrument -w -r -e class lab.recovery.benchmark.RefactorMapBenchmark \
  lab.recovery.benchmark/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument -w -r -e physicalEndurance true \
  -e class lab.recovery.app.PhysicalOsmEnduranceTest \
  lab.recovery.app.test/androidx.test.runner.AndroidJUnitRunner
```

실기기 한 대에서 `install -r`로 해당 APK 쌍을 설치한다. 원본 benchmark JSON과 trace는 실행 직후 세트별 디렉터리로 복사하고, 같은 세트의 `switchO10ToO07Ms` 세 값은 `switch-ready-ms.json`에 배열로 저장한다. AndroidJUnitRunner 결과, `measureBlock`, expected/actual FrameTimeline 및 환경을 확인한 세트에 집계 도구를 사용한다. 메모리 로그는 계측 직전부터 `adb -d logcat -v epoch -T 1 -s PhysicalOsmEndurance:I`로 수집한다.

```bash
python3 -B tools/summarize_render_benchmark.py <set-1> <set-2> <set-3> --output benchmark.json
python3 -B tools/summarize_render_endurance.py <private-logcat> --output endurance.json
```

재사용한 Perfetto Trace Processor는 v59.1, RPC API 14다. 링크된 Android와 Perfetto 문서를 2026-11-26 KST에 확인했다. Android 12의 Java heap graph는 Java 참조와 dominator에 사용하고, 네이티브 할당은 별도 heapprofd의 샘플링 결과에서 본다. [Compose 단계와 상태 읽기](https://developer.android.com/develop/ui/compose/performance/phases) · [Canvas 렌더링 지연](https://developer.android.com/topic/performance/issues/render) · [ART heap graph](https://perfetto.dev/docs/data-sources/java-heap-profiler) · [heapprofd](https://perfetto.dev/docs/data-sources/native-heap-profiler)
