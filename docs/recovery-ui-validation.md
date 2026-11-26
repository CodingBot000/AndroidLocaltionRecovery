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
