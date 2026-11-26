# 위치 복구 UI 리팩터링과 검증

## Phase 0 · 기준선

원격 `main`의 `a732af2dddfb86514fbfa76a6d7971f31e8d23da`에서 `codex/recovery-ui-refactoring` 작업 브랜치를 시작했다. 기존 작업 폴더와 분리했으며 Android 36 ARM64 에뮬레이터 한 대에서 Debug 화면과 Release 상속 벤치마크 APK를 준비했다. 앱·Live·원본 계측 APK의 SHA-256과 기준 화면 원본은 로컬 `build/reports/recovery-ui/baseline/`에 보존한다. [기준선 CI](https://github.com/CodingBot000/AndroidLocaltionRecovery/actions/runs/45790812053)

| 확인 대상 | 현재 구현 | 표시에 사용할 근거 |
|---|---|---|
| 신호·위치·경로 | `RecoveryLab.DriveHud`가 세 상태를 작은 텍스트로 읽고 최근 수신 정확도를 함께 표시한다. | `TrackingSnapshot.signal`, `source`, `route`는 독립 상태다. |
| Fix age·추정 | `lastAcceptedAtMs`는 측정 시각이다. 활성 DR 앵커는 마지막으로 채택한 관측에서 만든다. | 엔진 시각과 채택 측정 시각의 차이. 추정이 활성일 때만 세션의 `TrackingSettings.drMaximumMs`로 남은 상한 시간을 표시한다. |
| 재획득 | `recoveryCandidates`만 HUD에 전달된다. | 분모에는 실제 `ReplaySession`이 사용한 `TrackingSettings.recoveryCount`를 연결한다. `SIGNAL_RECOVERED`는 이벤트로 표시한다. |
| 관측·지도 | `TrackingMap`이 최근 1,200ms의 원시 관측을 표시하고, `MapLayers`가 원시 관측에 공통 X를 사용한다. | 유효한 수신 시각과 실제 `DecisionEvent`의 관측 식별자를 함께 확인한다. 지도 범례는 renderer의 의미 색상을 재사용한다. |
| 판정과 결과 | 제한된 `ReplayHistory`와 완료한 단일 실행의 `Evaluation`이 있다. 결과 상세와 진단은 목록 아래에 있다. | 주요 이벤트는 현재 실행의 실제 버퍼에서 고른다. 비율·표본 수·paired 비교·기대 판정 결과는 `Evaluation`을 사용한다. |
| 대표 시나리오 | `ScenarioMenu`는 전체 목록을 지원하고 기본값은 ODEMO_LONG이다. | OL07, OL02, OL10, OL13과 ODEMO_LONG은 기존 카탈로그 항목을 선택한다. 기대 시각은 화면에 사용하지 않는다. |
| Live | 서비스 상태·권한·수신 출처·지도는 `LiveState` 및 `LiveReading`이 제공한다. | 기존 Start/Stop과 `FLAG_SECURE` 안에서 상태를 묶어 표시한다. |

`RefactorMapBenchmark`는 `map-status`의 지도 ID, 실제 지도 준비, 활성 재생 버튼, 가상 시간과 카메라 조작을 소비한다. 접근성 계측은 동일한 가상 시간과 조작 태그를 읽는다. 기본 지도 높이 공식과 줌 계약을 유지하고, 원본 Macrobenchmark로 전환·주행·콜드 기동을 비교한다. TTFD 또는 프레임 시한 초과 P95가 10% 이상 악화하거나 프레임 시한 초과가 5ms 이상 늘면 반복 측정 및 원인 검토 대상으로 삼는다.

기기 목록에서 물리 단말 연결은 확인되지 않았다. 실기기 장시간 시험과 물리 GPS 검증은 연결 및 승인 상태를 기준으로 판정한다. 에뮬레이터의 성능 결과는 에뮬레이터 범위로 기록한다.

기준 화면은 1080×2400, density 420, en-US, 글꼴 100%, Day 지도의 기본 Follow 상태에서 확인했다. 실제 앱의 ODEMO_LONG 28초, OL07의 추정·중단·재획득·복구와 결과, OL02 거부, OL10 모호성, OL13 이탈·복귀, 전체 목록과 진단 화면을 확인했다. OL07의 추정 54,000ms, 중단 63,000ms, 재획득 71,000ms, 복구 73,000ms는 캡처 시점의 실제 상태다. 초기 목록 캡처에서 팝업용 resource ID 대기가 유효하지 않아 실제 목록 텍스트를 기다리도록 캡처 계측을 고쳤고, 원본 APK에서 캡처 계측 1개가 통과했다. 작은 HUD와 잔류 GPS 정확도, 전체 목록 길이, 지도 마커 겹침, 화면 아래 진단·결과를 확인했다.

## Phase 1 · 정보 배치

`RecoveryLab.kt`와 한·영 리소스에서 기본 헤더에 가상 주행 모드를 표시하고, 기존 지도 높이를 유지한 채 재생 조작을 지도 바로 다음에 배치했다. HUD, 지도, 범례는 목록에서 별도 항목으로 구성했다. 색상은 짙은 남색 계열과 상태별 강조를 사용한다. `:feature:lab:compileDebugKotlin`과 원본 기준선 Macrobenchmark 3회×3개 시나리오가 통과했다. 후보의 화면과 성능은 Phase 8에서 확인한다.

## Phase 2 · 위치 HUD

`DriveHud.kt`에서 신호, 위치 출처, 경로를 독립적으로 표시하고 실제 채택 측정 시각에서 Fix 경과를 산출했다. `ReplayController`는 세션에 전달하는 동일 설정 인스턴스를 읽기 전용으로 제공한다. 활성 DR의 상한 시간과 재획득 분모에 이 설정을 사용한다. 과거 관측 정확도는 마지막 수신값으로 표시하고 속도는 최신 채택 관측에 한정했다. `:feature:lab:testDebugUnitTest :core:simulation:test`가 통과했다. 새 단위 테스트 3개는 추정 활성·중단, 실제 설정값, null·stale과 후보 분모를 확인했다.

## Phase 3 · 지도 마커와 범례

`MapLayers.kt`의 동적 레이어에서 원시 GPS의 링, 실제 거부 관측의 X, 화면 밖 관측 방향을 나눴다. 겹치는 보조 마커의 외곽을 조정해 실제 투영 좌표와 차량 방향을 함께 읽을 수 있다. `TrackingMap.kt`는 실제 관측과 엔진 판정을 연결한다. 범례의 색은 지도 테마에서 가져오고 Truth 토글과 상세 범례를 제공한다. 정적 지도 경로 및 준비 방식은 동일하다. `:ui:map:testDebugUnitTest :feature:lab:testDebugUnitTest`가 통과했다. 추가 테스트 3개는 좌표·겹침·화면 밖 표시, 테마별 팔레트 및 관측별 거부 판정을 확인했다.

## Phase 4 · 주요 판정과 상세 진단

`RecentDecision.kt`에서 현재 run ID의 최근 주요 이벤트 하나를 지도 위에 시간과 원래 reason code로 표시했다. 일반 채택과 Tick은 요약에서 제외하고, 시간 변경만으로 주요 판정의 접근성 공지가 반복되지 않도록 텍스트의 live region을 분리했다. `LabDiagnostics.kt`는 접근 가능한 대화상자에서 제한된 기존 전체 로그를 지연 렌더링한다. `:feature:lab:testDebugUnitTest` 통과. 새 테스트 2개는 엔진 순서, 일반 판정의 제외, 실행 전환을 확인했다.

## Phase 5 · 대표 사례와 재생

`ScenarioMenu.kt`에서 전체 목록 옆에 다섯 대표 사례와 현재 선택을 표시했다. OL07을 먼저 제시하며 ODEMO_LONG이 기본이다. `PlaybackControls.kt`에서 재생 상태와 현재 배속을 한 줄로 표시하고 완료 시 재실행 버튼을 명확히 했다. 기존 배속 범위와 조작 태그는 유지하고 진행률 읽기를 지연했다. Debug 앱 및 계측 APK 빌드와 API 36 계측 2개가 통과했다. 실제 대표 사례 5개를 선택했으며 기존 배속 단계와 가로 드래그를 확인했다.

## Phase 6 · 가상 주행 결과

`RunResults.kt`가 단일 실행의 매칭 정확도·커버리지, 복구 지연과 이탈 권고를 요약하고 기존 상세 분포·SHA·판정 집계를 제공한다. paired 전후 오차는 동일한 채택 표본 수를 확인한 값이다. 완료 시 결과 버튼을 첫 화면에 노출하고 실행 세대에 묶었다. 결과 단위 테스트 3개, Debug·계측 빌드, API 36의 15초 데모 결과와 독립 JVM 평가 일치 계측이 통과했다.

## Phase 7 · Live 화면

`LiveActivity.kt`와 `LiveReadout.kt`에서 실제 위치 모드, 서비스·권한·fix 상태, 모의 위치 여부, 마지막 수신 정확도 및 수신·처리·드롭을 묶어 표시했다. 기존 권한 질의와 Start/Stop 경로, 지도·경로 선택, `FLAG_SECURE`를 사용한다. Live 단위 테스트 2개와 API 36 lifecycle 계측 3개가 통과했다. 첫 표시 단언은 서비스가 이미 활성 상태일 때 화면이 아직 시작 중으로 표시되는 시점에 실행됐다. 기존 서비스 단언을 유지하고 새 화면 단언이 해당 접근성 노드를 기다리도록 보완했다.

## Phase 8 · 회귀 및 실행 검증

모듈 경계, JVM·Android 단위 테스트 83개, 앱·지도·Live Lint, Debug/unsigned Release·계측·Benchmark APK, fixture 해시·재생성·APK 권한/격리 검사가 통과했다. S 계열, 기존 DEMO, 합성 장시간, OSM 및 OSM 고난도 평가 JSON 5종이 기준 결과와 바이트 단위로 일치했다. Live에 한국어 UI를 추가한 뒤 Lint가 기존 앱 이름 및 알림 문자열의 번역을 요구해 한국어 값을 연결했다.

API 36의 오프라인·시나리오·재생·지도 전환 계측 12개, 실제 상태별 캡처 계측 1개, 320×640·density 160·ko-KR·글꼴 130% 야간 지도와 조작 접근 계측 1개, TalkBack이 실제 활성화된 접근성 계측 1개가 통과했다. 후보의 화면에서 OL07 정상·추정·중단·재획득·복구와 결과, OL02 위치 점프 거부, OL10 모호성, OL13 이탈·복귀를 확인했다.

OL07의 실제 5× 재생 결과가 독립 재생 결과와 일치했고, 결과 대화상자를 연 뒤 Reset에서 이전 결과가 해제됐다. API 36 Live 자원 정리 계측 7개도 통과했다.

초기 후보의 지도 전환 Frame Overrun P95는 40.5→52.0ms로 늘었다. 전환 trace 9개씩에서 UI 스레드 실행 중앙값은 93.2→97.2ms, RenderThread는 199.3→162.7ms였다. 실행 시간 중앙값으로 느린 프레임의 원인을 확정할 수 없었다. HUD, 최근 판정, 지도를 하나의 지연 목록 항목에 묶고 상태 읽기는 각각의 Composable에 유지했다. 지도 높이 공식과 조작을 유지했다. 재시험 첫 세트의 전환 P95는 42.3ms였다.

### 최종 후보 성능 비교

기준선 A는 `a732af2`, 최종 앱 UI B는 `3e35cf8`이다. 동일 API 36 ARM64 SwiftShader 에뮬레이터의 1080×2400·density 420·글꼴 100%에서 Release 상속 profileable APK와 원본 `RefactorMapBenchmark`를 사용했다. 벤치마크 앱 locale은 한국어이고 컴파일 모드는 `None`이다. 원본 A와 최종 B는 시나리오별 3회씩 3세트다. 먼저 A1–초기 B1–A2–초기 B2–A3–초기 B3를 실행했고, 구조 변경 후 최종 B1–B2–추가 A4–B3를 실행했다. 에뮬레이터 제한 경고만 계측 인자로 허용했다. 실기기 수치와 합산하지 않는다.

| 지표 | 기준선 A | 최종 B | 추가 기준선 A4 |
|---|---:|---:|---:|
| O10 콜드 TTID 중앙값 | 207.3ms | 204.8ms | 219.3ms |
| O10 콜드 TTFD 중앙값 | 774.0ms | 800.7ms | 795.8ms |
| O10→O07 지도 준비 중앙값 | 486.6ms | 946.9ms | 476.5ms |
| 전환 Frame CPU P95 | 56.7ms | 60.6ms | 63.1ms |
| 전환 Frame Overrun P95 | 40.5ms | 44.7ms | 46.8ms |
| 주행 Frame CPU P95 | 365.1ms | 364.9ms | 365.4ms |
| 주행 Frame Overrun P95 | 519.1ms | 513.8ms | 520.9ms |

Frame P95는 각 빌드의 원시 표본을 합산한 nearest-rank 값이다. 전환 표본은 60→54개, 주행은 457→454개였다. 최종 전환 Overrun은 4.1ms·10.2% 상승해 상대 조사 기준을 넘었고 추가 A4에서는 그보다 높은 46.8ms가 관측됐다. TTFD와 주행 Overrun은 조사 기준 이내였다.

원본 테스트의 전환 준비 조건을 그대로 둔 별도 계측 APK로 A/B 각각 6회 진단했다. 지도 전환 요청 호출의 중앙값은 8.7→9.6ms였다. 지도 ID가 접근성 트리에서 확인될 때까지는 463.7→575.7ms였고 A 174.9–990.4ms와 B 26.7–1,011.9ms 범위가 겹쳤다. 이후 지도 귀속 확인은 2.8→2.1ms, 활성 재생 버튼 확인은 6.6→2.3ms였다. 기본 지도 높이 공식과 카메라 축척은 유지했다. 같은 에뮬레이터에서 접근성 트리의 지도 가시 경계는 1,016×1,215→1,016×1,210px였다.

전환의 지도 준비 대기에는 회귀 신호가 남아 있다. 대기 차이는 주로 지도 ID를 관측하기 전 구간에 있었고, 현재 자료로 실제 로딩과 접근성 관측 지연의 기여도를 확정할 수 없다. 전환 성능은 수용 PASS로 표시하지 않았다. 물리 단말의 Macrobenchmark, O10 180초×10회 내구·PSS 및 물리 GPS 시험은 단말 미연결로 BLOCKED다. [익명화 반복별 측정](benchmarks/recovery-ui-api36-emulator.json)

### 최종 환경별 회귀

| 환경 | 실제 결과 |
|---|---|
| API 26 · 앱 힙 48MiB | O10 스모크와 20회 지도 전환·재생 및 재생성 2개 PASS. Live lifecycle 2개 PASS, API 30+ 테스트 provider 계측 1개는 기존 조건에 따라 건너뜀. |
| API 31 | 오프라인 스모크 1개와 Live lifecycle 3개 PASS. |
| API 33·34 | 각각 오프라인 스모크 1개와 Live lifecycle 3개 PASS. 위치 권한 거부·대략 위치, 알림 권한 거부, 알림에서 Stop까지 확인했다. |
| API 36 · ARM64 | 최종 UI 오프라인·재생·전환 계측 13개 PASS. OL07 5×와 독립 평가 일치, 결과 진입과 Reset 포함. 실제 캡처, 한국어·글꼴 130% 야간 소형 화면, TalkBack을 각각 재실행해 PASS. Live lifecycle 및 정리 계측 7개 PASS. |

API 26의 초기 후보에서 기존 `UiDevice.pressHome()` 반환값 단언이 실패했다. 기준선 비교에서는 통과했고, 후보의 추가 계측에서 false 반환 뒤 실제 런처가 화면에 표시됨을 확인했다. Home 키 동작의 결과를 화면에 표시된 런처로 검증하도록 계측을 보완했다. Home과 화면 OFF 상태의 FGS·리스너 및 복귀 시 동일 토큰·Stop 단언은 유지했다. 보완 후 전체 API 26 Live 계측을 다시 실행했다. 이 차이는 계측 동기화에서 확인됐으며 운영 Live 코드는 변경하지 않았다.
