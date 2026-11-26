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
