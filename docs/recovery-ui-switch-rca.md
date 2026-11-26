# 지도 전환 성능 조사

## F0 · 비교 기준

원격 `main`과 [초안 PR #9](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9)를 조회했다. 기준 A는 `a732af2dddfb86514fbfa76a6d7971f31e8d23da`, 이전 UI 후보 B는 `3e35cf85446d9a2fc8f0079232ea58a0197e43ad`다. 후속 작업 시작 당시 PR head `8c572cd1d5e8c1378834310bccab43b058f16e4a`에서 B와 제품 소스·빌드 설정이 동일했다. 당시 PR/push의 두 작업이 각각 성공했다. 기존 측정값과 결론은 별도 이력으로 보존한다. [기존 반복별 원시 집계](benchmarks/recovery-ui-api36-emulator.json)

JDK 17과 Gradle 8.13, SDK 36에서 A/B를 별도 worktree에 빌드했다. 원본 `RefactorMapBenchmark` APK 두 파일은 바이트 단위로 일치했다. 성능 대상은 Release 상속 profileable APK이며 `CompilationMode.None()`을 사용한다. 원본 전환은 O10에서 `am start … --es benchmarkSelect OL03`을 보내고 O07 지도 상태, OSM 출처, 활성 재생 버튼을 그 순서로 기다린다. FrameTimingMetric의 원래 블록에는 O10 복귀도 포함된다. 앱 전환은 `MainActivity` → `LabViewModel` → `ReplayController` 큐 → `ScenarioRepository` → READY → Compose와 `RoadMap`을 거친다. 기존 `onMapReady`는 지도 geometry 준비 후 프레임 콜백을 기다린다.

에뮬레이터는 API 36 ARM64 SwiftShader 한 대에서 1080×2400, density 420, 약 60Hz, 앱 ko-KR, 글꼴 100%로 실행했다. 첫 A 예비 세트는 장비에 남아 있던 글꼴 130%에서 완료되어 본 비교에서 분리했다. 에뮬레이터 오류만 벤치마크 인자로 허용했다. 시작 시 연결된 실기기는 없었다.

### 본 시험 전 조사 기준

TTFD 중앙값의 10% 이상 증가, 주행·전환 Overrun P95의 10% 이상 또는 5ms 이상 증가, 지도 전환 준비 중앙값의 10% 이상 또는 50ms 이상 증가를 조사한다. 기준 Overrun이 0 이하인 경우에는 상대 변화 대신 절대 차이와 분포를 검토한다. TTID와 Frame CPU도 별도로 기록한다. P95는 유효한 원시 프레임을 합산한 nearest-rank 값이며 기동·지도 준비는 반복의 중앙값이다. 실제 단말 메모리는 완료 중앙값·최대 PSS·120초 유휴 전체/네이티브 PSS의 10% 이상 증가, 반복 상승, 정리 실패·OOM이 조사 대상이다. 임계값을 넘은 결과는 동일한 고정 조건의 반복과 내부/외부 구간 증거로 판정한다.

## F1 · 구간 재현

원본 APK A1–B1–B2–A2를 시나리오별 3회 실행했다. 지도 준비 중앙값은 A 194.3ms, B 460.1ms였고 전환 Overrun P95는 52.4ms→42.5ms였다. 이전 486.6ms→946.9ms와 같은 방향의 외부 지연을 관측했고 전환 프레임 악화는 이 재실행에서 재현되지 않았다. A/B 각 6회는 기존 9회 자료와 독립 집계했다.

별도 진단 APK A1–B1–B2–A2에서도 같은 Select/대기 경로를 사용했다. 진단 요청의 T0는 `elapsedRealtimeNanos`, 초기 전달은 T1/T1q, LOADING 실행 발급은 T2, I/O 대기·캐시·파싱은 T3–T4, 세션 준비·READY 전달은 T5–T6u다. T7은 지도 ID가 있는 HUD의 Compose `SideEffect`이며 T7l은 노드 레이아웃 좌표를 받는 별도 콜백이다. T8–T9p는 지도 worker 준비·publish, T10은 정적 지도 draw 명령의 첫 기록, T11은 기존 프레임 콜백이다. U1/U2/U3는 원래 순서로 얻은 접근성 관측값이다. Android 단조 시각과 trace clock의 BOOTTIME 일치를 확인하고 T10을 같은 앱 UI 스레드의 `Choreographer#doFrame`에 연결했다. 그 vsync로 MainActivity surface token 및 SurfaceFlinger display token을 검색했다. T10과 T11의 순서나 시간 차이를 GPU present 자체로 해석하지 않는다.

| 요청별 구간 (ms) | A 중앙값 [범위] | B 중앙값 [범위] |
|---|---:|---:|
| 전환 전달 T0→T1 | 8.9 [7.9–9.4] | 8.6 [7.7–9.1] |
| 실제 fixture I/O·파싱 T3w→T4 | 9.9 [9.6–10.8] | 9.6 [9.0–10.5] |
| READY T0→T6 | 26.9 [21.3–33.7] | 27.1 [23.2–32.7] |
| UI 상태→HUD 배치 T6u→T7l | 10.7 [6.6–12.5] | 8.6 [6.9–15.3] |
| 지도 준비 worker T8w→T9 | 2.2 [2.0–2.3] | 2.8 [2.3–4.3] |
| 첫 지도 draw T0→T10 | 55.6 [51.4–59.8] | 56.6 [51.5–79.9] |
| 첫 draw에 대응하는 SurfaceFlinger present | 119.0 [113.9–159.0] | 122.9 [115.6–194.3] |
| HUD commit→지도 ID 관측 T7→U1 | 483.8 [141.0–973.5] | 973.5 [656.4–1,694.4] |
| 요청→활성 재생 노드 확인 T0→U3 | 641.4 [189.0–1,028.1] | 1,011.6 [710.8–2,038.9] |

각 열은 해당 요청에 연결된 6개 표본이다. T10의 실제 지도 캔버스는 양쪽 모두 1,016×1,215px, Follow·Detail, 축척 2.1167px/m였다. 이전 접근성 트리의 가시 경계 차이 5px는 캔버스 크기 차이로 재현되지 않았다. 원본 trace의 measureBlock UI 실행 중앙값은 92.7→96.6ms, RenderThread 205.2→203.1ms, JIT 36.7→41.0ms였다. 각 스레드의 실행·runnable 구간, Compose/GC slice 및 app deadline token은 별도 기록했다. 스레드 실행 시간 및 중첩 구간은 전체 벽시계에 합산하지 않았다. [반복별 수치와 trace 연결 결과](benchmarks/recovery-ui-switch-rca-api36.json)

## F2 · 원인 격리와 수정 결정

**확인** · 조사한 에뮬레이터의 큰 외부 준비 편차는 대부분 첫 화면의 present 이후 UIAutomator가 새 접근성 노드를 관측하기까지 발생했다. 같은 B APK 및 같은 진단 계측 APK에서 U1 poll 직전에 `UiAutomation.clearCache()` 호출만 켰다. 순서는 기본–캐시 제거–캐시 제거–기본이며 각각 3회씩이다. 기본 대비 캐시 제거의 첫 present 중앙값은 121.6→116.9ms, U1 관측은 628.2→108.6ms, U3는 639.6→126.9ms였다. 일부 관측은 present 전에 끝났다. 접근성 노드 준비와 화면 표시 순서는 서로 독립적으로 기록한다. 기본 B의 `UiDevice#waitForIdle` 합계 중앙값은 2.7ms였으며 이 대기 호출만으로 수백 ms의 차이를 설명할 수 없다.

**해당 범위에서 배제** · 수백 ms 크기의 큐/자산 읽기, 세션 초기화, Compose HUD 배치 및 지도 준비·첫 표시 지연은 같은 요청의 단계 비교로 배제했다. B의 소규모 지도 준비 차이와 최댓값 증가를 숨기지 않았다. 반복 조건의 스케줄링과 프레임 부하는 서로 달라졌으며 이전 프레임 P95 지연의 전체 원인과 각 플랫폼의 접근성 캐시 무효화 시점은 미확정이다.

제품의 전환·캐시·지도 렌더러와 원본 계측은 유지했다. 캐시 제거는 원인을 격리하기 위한 진단 변형에서만 실행했다. 원래 성능 비교의 선택 조건, 제한 시간 및 세 종료 신호를 보존한다. 외부 측정값은 최종 제품 비교에서 별도로 보고한다.

## F4 · 초기 UI 후보 C1

초기 앱 C1은 `664d8f5`의 제품 코드다. 동일 하네스에서 A1–C1–C2–A2–A3–C3 순서로 시나리오마다 A/C 각각 9회를 실행했다. 1080×2400·density 420·ko-KR·font 100%에서 API 36 ARM64 SwiftShader로 측정했다. 애니메이션 스케일은 모두 0, 시뮬레이션 배터리 온도는 25°C, thermal status는 0이었다. 각 세트의 호스트 load average와 원본 해시는 JSON에 남겼다.

| 원본 측정 (ms) | A 9회 | C 9회 |
|---|---:|---:|
| 콜드 TTID 중앙값 | 200.9 | 200.0 |
| 콜드 TTFD 중앙값 | 784.5 | 796.7 |
| O10→O07 외부 준비 중앙값 | 470.6 | 414.2 |
| 전환 Frame CPU P95 | 58.9 | 64.0 |
| 전환 Frame Overrun P95 | 42.7 | 48.0 |
| 주행 Frame CPU P95 | 358.8 | 361.6 |
| 주행 Frame Overrun P95 | 510.1 | 510.0 |

전환 표본은 60→72개, 주행은 461→469개다. 전환 Overrun의 5.3ms·12.5% 상승으로 원본 전환 케이스를 C4–A4–A5–C5 독립 세트로 재시험했다. 추가 A/C 각각 6회의 준비 중앙값은 484.8→836.0ms, Overrun P95는 48.0→61.2ms였다. 원시 표본 39→50개를 집계했다. 초기 본 시험과 추가 세트를 각각 보존했다. 이전 B의 이번 재실행 6회 준비 중앙값은 460.1ms, 전환 Overrun P95는 42.5ms였으며 C와 같은 세트에서 직접 대응한 값은 아니다.

별도 A/C 진단 전환 각 6회에서 요청→READY 중앙값은 24.1→24.3ms, 첫 draw는 55.4→51.9ms, 대응 surface의 첫 present는 121.4→118.3ms였다. 실제 지도 캔버스는 A와 C에서 동일한 1,016×1,215px, Follow·Detail, 2.1167px/m였다. 따라서 별도 크기 고정 변형을 추가할 필요가 없었다. 각 present 이후 외부 준비 관측까지의 중앙값은 257.5→413.6ms였다. 원본 재시험에서 관측한 준비 시간의 재상승을 첫 화면의 지연으로 환산할 수 없다.

진단 trace에서 동일 토큰을 일대일로 연결한 app surface의 Overrun P95는 U3 전 A 54.1ms(21개) → C 45.8ms(21개), 이후 A 9.1ms(16개) → C 1.4ms(24개)였다. U3 이후에는 O10 복귀가 들어간다. 이 값과 AndroidX 원본 FrameTimingMetric은 별개다. 원본 재시험의 앱 UI 실행 중앙값은 93.0→108.1ms이며 RenderThread는 212.3→211.3ms였다. C의 느린 프레임 일부는 같은 반복에 집중됐고, 가장 큰 프레임도 유효 표본에 포함했다.

**INCONCLUSIVE** · 지도 첫 표시 지연은 이 비교에서 재현되지 않았지만 원본 전환 P95의 조사 임계값 초과는 두 시험에 남았다. 진단 trace에서 같은 방향의 앱 프레임 지연을 재현하지 못해 반복별 프레임 비용과 관측·스케줄링의 기여도를 확정하지 못했다. 확인된 별도 제품 원인이 없어 추가 렌더러 변경은 적용하지 않았다. [초기 후보 반복·원시 프레임·trace 요약](benchmarks/recovery-ui-pr9-c1-api36.json)

## F4 · 최종 제품 성능

최종 C `ce617de21ffbf5485564c9d7bc589a3b6085c02c`는 71초 재획득 화면과 소형 화면의 시나리오 제목을 보완했다. 동일 A와 C를 이전과 동일한 API 36 ARM64 SwiftShader, 1080×2400·density 420·ko-KR·글꼴 100%로 새로 A1–C1–C2–A2–A3–C3 순서로 측정했다. 각 셀은 시나리오별 9회로, 같은 원본 계측 APK와 `CompilationMode.None()`을 사용했다.

| 최종 원본 측정 (ms) | A | C |
|---|---:|---:|
| 콜드 TTID 중앙값 | 199.3 | 205.0 |
| 콜드 TTFD 중앙값 | 790.6 | 828.7 |
| O10→O07 외부 준비 중앙값 | 406.8 | 411.1 |
| 전환 Frame CPU P95 | 103.9 | 104.9 |
| 전환 Frame Overrun P95 | 89.8 | 92.3 |
| 주행 Frame CPU P95 | 446.1 | 459.2 |
| 주행 Frame Overrun P95 | 600.4 | 631.2 |

원시 프레임 표본은 전환 A 53/C 65개, 주행 A 431/C 438개였다. A와 C 모두 초기 후보를 측정한 구간에 비해 프레임 부하가 커졌다. 세트별 호스트 부하 및 열 상태를 정규화 데이터에 남겼다. 지도 준비와 전환 P95는 최종 본 시험의 조사 기준 이내였다. 이전 B의 준비 946.9ms와 비교할 수 있으나, 실행을 짝지은 표본은 이 표의 A/C뿐이다.

별도 진단 전환 각각 6회에서 A→C의 READY 중앙값은 25.0→30.7ms, 첫 draw는 54.1→68.2ms였다. 대응 SurfaceFlinger 첫 present는 165.0→187.7ms이며 각 범위는 111.1–182.5ms와 119.3–198.7ms였다. U3에서 대응 present를 뺀 관측 지연은 546.1→100.4ms였다. 실제 canvas는 양쪽 1,016×1,215px, Follow·Detail 및 2.1167px/m였다. C의 이 진단 첫 화면이 약 23ms 늦게 관측된 사실과 원래 수백 ms의 외부 관측 차이를 각각 기록한다.

주행 Overrun의 절대 증가 30.9ms로 원래 주행 케이스를 재실행했다. 코드나 조작은 동일했다. C6은 시작 시 Gradle 평가·trace 변환이 겹친 사실을 시각으로 확인해 독립 비교에서 제외했다. 원본과 사유는 보존하고 C8을 추가했다. 유효 추가 A6/A7 및 C7/C8 각 6회에서 주행 P95는 673.8→1,023.3ms였다(312→310 프레임). 후보 세트별 P95는 C7 1,287.8ms, C8 550.7ms였다. RenderThread 실행 중앙값은 C7 6,866.3ms, C8 3,820.0ms였고 A 두 세트도 3,180.3ms와 5,733.3ms로 달랐다. 이 범위에서는 원본 주행 오차와 호스트 환경을 제품 프레임 비용과 분리해 확정할 수 없었다. **에뮬레이터 성능 수용은 INCONCLUSIVE다.** [최종 원시 반복, 재실행 정규화 데이터와 trace 분석](benchmarks/recovery-ui-pr9-final-api36.json)

## 재현

원본 비교에서는 각 SHA의 깨끗한 worktree에서 `:app:assembleBenchmark :benchmark:assembleBenchmark`를 실행한다. 동일한 원본 계측 APK를 설치하고 전체 `lab.recovery.benchmark.RefactorMapBenchmark`를 실행한다. 원래 `switchO10ToO07Ms` 세 개를 같은 세트의 `*benchmarkData.json` 옆 `switch-ready-ms.json` 배열로 저장한다. `tools/summarize_render_benchmark.py`는 여러 세트의 원시 프레임과 반복을 읽는다. 에뮬레이터에서는 `-e androidx.benchmark.suppressErrors EMULATOR`만 적용한다.

진단은 별도로 체크아웃한 깨끗한 worktree와 전용 APK에서 실행한다. `probe_a`와 `probe_b`는 각각 A와 B worktree 경로다.

```bash
python3 -B tools/recovery_switch_probe.py "$probe_a"
python3 -B tools/recovery_switch_probe.py "$probe_b"
# 각 worktree에서 빌드한 뒤 A/B 대상 APK와 동일 진단 계측 APK를 교대로 설치한다.
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark
adb shell am instrument -w -r -e androidx.benchmark.suppressErrors EMULATOR \
  -e class 'lab.recovery.benchmark.RefactorMapBenchmark#switchO10ToO07' \
  lab.recovery.benchmark/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -v raw -s 'RecoverySwitchRCA:I' '*:S'
```

실행당 새 T0 세 개에 해당하는 `RecoverySwitchRCA` 메시지만 각 세트의 `switch.log`에 저장한다. 출력된 Perfetto trace는 같은 폴더에 보존한다. 각 세트 폴더명은 `A1` 등 비교군과 번호로 끝나야 한다. 계측 인자 `-e switchClearCache true`를 추가하면 U1의 동일 selector를 읽기 전에만 접근성 캐시를 비운다.

```bash
python3 -B tools/summarize_recovery_switch_probe.py diagnostic-*/switch.log --output intervals.json
python3 -B tools/analyze_recovery_switch_trace.py diagnostic-* --output traces.json
```

두 번째 명령은 로컬 Perfetto Python Trace Processor 환경을 사용한다. 원본 trace·APK·계측 로그는 로컬에 보존하고, 공개 JSON에는 해시·정규화 반복·집계값을 포함한다.
