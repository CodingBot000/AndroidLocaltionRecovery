# 위치 복구 UI 리팩터링과 검증

## 구현 및 회귀 검증 요약

최종 제품 `ce617de`에서 신호·위치 출처·경로 상태와 실제 엔진 판정을 화면에 연결했다. GPS 손실·추정 중단·재획득을 동일 가상 주행에서 재현하고 판정 로그와 결과를 대조했다.

| 영역 | 확인한 결과 |
|---|---|
| UI 및 재생 | 정상·DR·중단·재획득·복구 화면, 실제 1×·5× 재생의 독립 평가 일치, 범례·재생 조작과 TalkBack |
| 회귀 | 단위 테스트 83개, 기존 평가 JSON 5종 바이트 동일, API 26·48MiB 및 API 31·33·34·36 플랫폼 계측 |
| 실기기 기능 | O10 180초×10회 내구·Reset, 지도 전환·재생 및 Live 시작·종료 20회 |

[실행 화면](demo.md) · [상세 아키텍처](architecture.md) · [기능·시험 근거](verification.md)

## PR #9 후속 검증

후속 작업의 제품 코드와 새 화면·성능 측정 기준은 `ce617de`다. [지도 전환 RCA](recovery-ui-switch-rca.md)에서 원본 재실행, 요청별 내부·외부 구간, 접근성 캐시 대조를 기록했다. 아래 Phase 0–10은 최초 UI 후보의 검증 이력이다.

### F0–F2 · 기준선과 지도 전환

시작 시 원격 `main` 및 PR head가 명세와 일치했다. A/B 격리 빌드에서 원본 계측 APK가 동일했다. 같은 요청에서 새 지도의 첫 present가 끝난 뒤 외부 접근성 노드 검색이 늦어지는 구간을 확인했다. 동일 B의 진단 캐시 대조에서 U3 중앙값은 639.6→126.9ms, 첫 present는 121.6→116.9ms였다. 진단 전용 코드는 제품 및 원본 벤치마크에 적용하지 않았다.

### F3 · 첫 화면과 재생

`RecoveryLab.kt`에서 지도 높이와 카메라 계약을 유지하면서 제목과 모드를 같은 줄에 두고, 시나리오의 지역 이름을 짧게 표시했다. 같은 설명을 눌러 카탈로그의 전체 내용을 펼칠 수 있다. Fix 경과, DR 상한, 재획득 후보와 추정 중단 사유는 실제 세션 상태에서 독립 표시한다. `Latest significant event`와 발생 시각·reason code는 현재 상태와 별도로 읽힌다. 이제 지도 아래에서 범례·Truth, Play·Step·Reset, 배속 진입을 찾을 수 있다. 배속 창의 5× 바로 선택과 0.5×–5× 슬라이더를 실제로 조작했다.

첫 배치의 54초 화면에서 재생 버튼 하단이 잘려 재생 상태를 범례 행에 옮겼다. 첫 계측의 가로 드래그는 좁아진 창에서 트랙 밖을 시작점으로 잡았다. 실제 트랙 안쪽에서 시작하는 터치로 0.5×→5× 이동을 재시험했다. 최종 표준 화면에서 첫 화면 노드의 클립 여부를 비교하고 5× 실행과 독립 평가 일치를 확인했다.

초기 후보 `664d8f5`는 71초에 추정 중단 이유와 재획득 후보가 함께 줄바꿈되어 하단 재생 버튼 일부가 잘렸다. 최종 제품은 `LOST + LAST_KNOWN`에 추정 중단 사유를 표시하고, `RECOVERING`에서는 현재 후보를 강조한다. 이전 중단 사유는 진단과 이벤트에 남는다. 표준 크기의 OL07 비완료 상태마다 실제 재생·Step·Reset·배속·범례·Truth 컨트롤의 가시 높이를 정상 상태와 비교했다. 좁은 화면의 시나리오 선택 제목은 두 행까지 허용한다.

### F4 · 성능과 기능 회귀

초기 UI 후보 `664d8f5`의 원본 전환 프레임 지표는 두 차례 조사 임계값을 넘었다. 71초 화면을 보완한 `ce617de`는 같은 원본 하네스에서 다시 측정했다. 두 제품 후보의 데이터와 진단 trace는 각각 보존한다. [성능과 재시험](recovery-ui-switch-rca.md)

최종 G3는 API 36 ARM64 SwiftShader에서 주행만 A→C→C→A로 빌드별 6회 실행했다. Frame CPU P95는 365.85→365.83ms, Overrun P95는 520.49→521.45ms였다. 이전의 큰 Overrun P95 악화는 재현되지 않았다. 후보의 더 긴 중앙 프레임과 더 높은 deadline 태그 비율을 기록하고, 기존 P95 기준에서 **PASS**로 G3를 종료했다. [최종 주행·스케줄링·호스트 상태](recovery-ui-emulator-g3-final-ride.md)

모듈 경계, 단위 테스트 83개, Debug Lint, Debug·unsigned Release·계측·Benchmark APK, fixture 해시·재생성·APK 권한 및 Live/HTTP 격리를 새로 실행했다. S 계열, 기존 DEMO, 합성 장시간, OSM, OSM 고난도 평가 JSON 5종은 이전 검증 결과와 바이트 단위로 일치했다. 오프라인 APK의 fixture 119개와 Live APK의 감사 대상 asset 31개를 확인했다.

첫 API 36 전체 계측 중 `longOutageShowsDrThenLastKnownAndRecovers`에서 Compose `SnapshotStateObserver`가 서로 다른 스레드에서 관측되는 예외가 한 번 발생했다. 같은 케이스 단독 재실행은 이전 B와 C에서 각각 2회 통과했다. Live 계측의 전체 디스패처 대안을 시험했으나 오프라인 앱의 초기 화면이 준비되지 않아 기각했다. 오프라인 계측은 연속 Step을 보낼 때만 테스트 프레임 클록을 정지하고 실제 엔진이 기존 경계 시각에 도달한 뒤 화면 작업을 재개하도록 동기화했다. 기존 단언·제한 시간과 제품 코드를 유지했다. [관련 Live 계측 조사](compose-slot-table-rca.md)

API 36 Live 정리 계측은 lifecycle 검사의 종료 정리 뒤 권한이 없는 상태에서 시작해 준비 검사가 실패했다. 에뮬레이터 실행에 필요한 권한을 다시 부여한 뒤 7개 모두 통과했고 종료 시 권한을 회수했다. 위치 스위치 검사는 Android의 `No location access` 시스템 창이 앱 상태를 가렸다. 해당 창을 닫고 기존 앱 표시와 FGS 정지·권한 철회·명시적 재시작을 재확인했다.

최종 API 36 오프라인 14개가 통과했다. OL07의 1×·5× 모두 실제 Play부터 완료까지 실행해 독립 `ReplaySession`과 같은 `Evaluation`을 얻었다. Play 입력부터 결과 관측까지 1×는 120.538초, 5×는 24.514초였다. 5× 시험에서는 결과를 연 뒤 Reset해 평가와 이전 판정 코드가 제거됨을 확인했다. 실제 TalkBack의 카메라와 재생 조작, 최근 판정 제목, 범례·배속 창의 열기·닫기와 슬라이더 접근도 통과했다. 320×640·한국어·글꼴 130%의 야간 화면에서는 전체 OL07 이름이 두 줄로 표시됐다. 스크롤 후 범례를 닫고 배속 창에서 5×를 선택했다.

API 36의 Live lifecycle 3개와 정리 계측 7개에 사용한 Live 앱과 계측 APK는 최종 빌드와 각각 바이트 단위로 일치했다. 정밀·대략 위치, 알림 허용/거부와 Stop, 위치 스위치 및 중단 복구를 시스템 UI에서도 검증했다.

| 환경 | 최종 제품의 플랫폼 검증 |
|---|---|
| API 26 · ART 48MiB | O10 기동 및 20회 전환·재생·재생성 계측 2개 통과. Live lifecycle 2개 통과. API 30+ 전용 모의 provider 1개는 기존 가정으로 건너뛰었다. |
| API 31 | 오프라인 1개, Live lifecycle 3개 통과. |
| API 33·34 | 각 오프라인 1개와 Live lifecycle 3개 통과. 위치 권한 거부·대략 허용, 알림 권한 거부 및 알림에서 Stop을 확인했다. |
| API 36 | 오프라인 14개, 실제 TalkBack, 한국어 글꼴 130% 소형 화면, 최종 실제 캡처, Live lifecycle 3개와 별도 정리 7개 통과. |

API 33의 첫 오프라인 실행과 표시 대기만 추가한 재실행에서는 테스트 터치 뒤 OSM 출처 대화상자가 열리지 않았다. 동일 제품의 실제 화면에서 Android 입력으로 출처 버튼을 탭해 라이선스·출처·닫기 버튼이 보이는 것을 확인했다. 계측도 화면에 보이는 Android 출처 버튼을 터치하도록 맞췄으며, 기존 표시 단언과 후속 지도·카메라 단언을 유지한 채 API 33에서 두 번 통과했다.

### F5 · 실기기

2026-11-26의 UI 후속 작업에서는 단말이 없어 F5가 BLOCKED였다. 2026-11-27 연결된 SM-G975N에서 G7을 실행했다. 같은 실기기의 지도 준비는 675.4→675.0ms, 주행 Overrun P95는 101.4→102.0ms였다. 콜드 TTFD는 본 측정 1,465.6→1,915.0ms, 독립 재시험 2,113.2→2,011.0ms로 방향이 달랐다. G7 성능 수용은 **INCONCLUSIVE**다. [G7 결과와 복원](recovery-ui-physical-g7.md)

G7 최종 콜드 재시험 A→C→C→A에서 TTID는 709.2→613.4ms, TTFD는 1,670.6→1,821.5ms였다. 최종 TTFD +9.0%는 조사 기준 이내였고 인접 비교 두 쌍의 차이 방향은 갈렸다. 후보 고유의 지속 회귀와 지연 원인을 확인하지 못해 **INCONCLUSIVE**로 G7을 종료했다. [최종 콜드 측정·trace·복원](recovery-ui-physical-g7-final-cold.md)

같은 단말에서 G8 독립 내구를 빌드별 한 번씩 실행했다. A/C 모두 O10 180초×10회와 Reset·유휴 표본, O01/O10 전환·재생 20회, Activity 재생성 및 Live 시작·종료 20회를 통과했다. 유휴 120초 전체 PSS는 384,256→380,640KiB, Native PSS는 103,730→114,467KiB였다. Native PSS가 조사 기준을 넘어 G8 수용은 **INCONCLUSIVE**다. [G8 결과와 복원](recovery-ui-physical-g8.md)

C→A 역순에서 빌드별 독립 내구 한 번과 Reset 뒤 60·120·300·600초 유휴 표본을 추가했다. 유휴 Native PSS 격차는 +7.40%였고 완료 중앙값과 최대값은 +10.53%와 +14.05%였다. 두 시험에서 반복된 후보의 더 큰 Native Heap PSS 완료 기울기는 원인 미확정의 잔여 위험이다. G8은 **INCONCLUSIVE**로 내구 검증을 종료했다. [G8 최종 재시험·진단·복원](recovery-ui-physical-g8-retest.md)

G9에서는 A/C 실제 GPS의 120초 smoke와 실패 대조를 실행했다. Live와 직접 GPS 요청 모두 위성·fix가 관측되지 않았다. 대기 UI와 FGS·리스너 해제는 확인했다. A의 Activity 재생성 3회와 화면 OFF 303.979초는 세션을 유지했다. Wake 뒤 수동 잠금 해제가 이뤄지지 않아 A의 복귀와 C의 수명은 BLOCKED였다. G9 판정은 **INCONCLUSIVE**다. [G9 결과와 복원](recovery-ui-physical-g9.md) · [실기기 범위와 이력](physical-device-validation.md)

G9 재시험은 사용자가 잠금을 해제한 홈 화면에서 시작했다. A의 Activity 재생성 3회와 화면 OFF 303.560초 동안 세션 유지를 다시 확인했다. Wake 뒤 90초 수동 해제 시간 초과가 재현돼 A 복귀·UI Stop, C 수명과 추가 GPS 대조는 BLOCKED다. G9 **INCONCLUSIVE**를 유지했다. [G9 재시험·복원](recovery-ui-physical-g9-followup.md)

G8 최종 재시험 준비 시 잠금이 해제된 단말의 홈 화면 복원을 확인했다. G9 추가 자동 GPS·수명 시험은 종료했다. [홈 복원 기록](recovery-ui-physical-g9-followup.md#복원과-증거)

### F6 · 실제 화면과 문서

공개 PNG 10장은 제품 `ce617de`의 Debug APK에서 실제 Step과 독립 재생을 대조해 촬영했다. API 36 ARM64, 1,080×2,400, density 420, en-US, 글꼴 100%, Day·Follow 조건이다. 새 캡처의 시나리오·시각·신호·위치 출처·경로·reason code 기록은 이전 UI와 바이트 단위로 일치했다. 실제 화면에 재생·배속·범례 조작이 보이는지 확인한 뒤 PNG 원본을 그대로 반영했다. [캡처 및 측정 APK의 해시](benchmarks/recovery-ui-pr9-final-api36.json)

| OL07 가상 시각 | 실제 화면과 판정 |
|---:|---|
| 28초 | TRACKING · MAP_MATCHED · ON_ROUTE. 22초 `AMBIGUOUS`는 최근 판정 이력이다. [정상](demo/recovery-ui/ol07-tracking.png) |
| 54초 | LOST · DEAD_RECKONED. Fix 경과 6초, DR 상한 잔여 9초. [추정](demo/recovery-ui/ol07-estimating.png) |
| 63초 | LOST · LAST_KNOWN. Fix 경과 15초, `DR_LIMIT`. [추정 중단](demo/recovery-ui/ol07-last-known.png) |
| 71초 | RECOVERING · LAST_KNOWN. 실제 재획득 후보 1/3과 `RECOVERY_PENDING`. [재획득](demo/recovery-ui/ol07-recovering.png) |
| 73초 | TRACKING · MAP_MATCHED · ON_ROUTE와 `SIGNAL_RECOVERED`. [복구](demo/recovery-ui/ol07-recovered.png) |
| 120초 | `RUN_COMPLETED`, 단일 가상 주행의 커버리지·paired 오차와 검사 결과. [평가](demo/recovery-ui/ol07-complete-results.png) |

README에는 정상·추정·복구 세 장과 추정 중단 링크를 배치했다. 상세 시연에는 OL02 거부, OL10 모호성, OL13 이탈·복귀의 실제 화면도 연결했다. 공개 자료는 가상 주행 캡처이며 `FLAG_SECURE`가 적용된 Live 화면은 계측으로 확인했다. 기존 OSM 갤러리와 이전 PR 캡처는 이력으로 보존했다. Markdown 30개에서 상대 링크와 앵커 372개를 검사했다. [실행 안내](demo.md#ol07-시연)

구현과 성능 시험을 종료했다. G3는 **PASS**, G7·G8·G9는 **INCONCLUSIVE**다. 반복 관측된 Native Heap PSS 증가와 현재 제품의 실제 GPS·잠금 이후 생명주기 미검증 범위는 별도 기록에 남겼다. [최종 수용 판단과 잔여 위험](recovery-ui-final-acceptance.md)

### F7 · 기존 PR과 수용 게이트

같은 `codex/recovery-ui-refactoring` 브랜치에서 초안 PR #9을 갱신한다. 제품·성능·캡처 코드는 `ce617de`이며 이후 커밋은 계측·수치 집계·문서·이미지다. 최종 head의 PR/push 검사 결과는 PR 설명과 검사 탭에서 확인할 수 있다. [PR #9](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9) · [최종 head 검사](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9/checks)

| 게이트 | 판정 | 근거 |
|---|---|---|
| G0 기준선·변경 추적 | PASS | A/B/C 소스, APK 및 환경을 기록하고 제품 diff를 확인했다. |
| G1 기존 전환 지표 | PASS | 원본 workload·선택 조건·종료 신호를 보존하고 시나리오별 9회를 수행했다. |
| G2 지도 전환 RCA | PASS | 동일 요청·run ID로 내부 준비·첫 frame present·외부 관측을 연결하고 접근성 캐시 효과를 대조했다. |
| G3 최종 에뮬레이터 성능 | PASS | 기존 기동·전환 근거와 최종 원본 주행 각 6회. 주행 Overrun P95 +0.96ms(+0.18%). [최종 주행](recovery-ui-emulator-g3-final-ride.md) |
| G4 화면 정보 및 조작 | PASS | OL07 상태별 캡처의 첫 화면 컨트롤과 실제 5× 선택을 확인했다. |
| G5 기능·엔진 동등성 | PASS | 단위 83개, 평가 JSON 5종 동일, 앱 UI·지도·카메라·재생·Live 계측 및 APK 검사. |
| G6 OS·접근성 | PASS | API 26·48MiB와 31/33/34/36, 한·영 및 글꼴 130%, 실제 TalkBack. |
| G7 실기기 성능 | INCONCLUSIVE | 원본 각 9회, 콜드 재시험 두 차례에서 각 6회. 최종 콜드 TTFD +9.0%, 인접 쌍의 차이 방향이 갈렸다. [최초 G7](recovery-ui-physical-g7.md) · [최종 G7](recovery-ui-physical-g7-final-cold.md) |
| G8 실기기 메모리·내구 | INCONCLUSIVE | A→C와 C→A에서 빌드별 각 독립 실행 1회. 반복된 후보 Native Heap PSS 증가를 잔여 위험으로 기록하고 종료했다. [최초 G8](recovery-ui-physical-g8.md) · [최종 G8](recovery-ui-physical-g8-retest.md) |
| G9 실제 GPS·서비스 | INCONCLUSIVE | A/C의 기존 Live·직접 GPS 무수신, 대기 UI와 해제 확인. A 화면 OFF 유지 재시험 통과, 잠금 후 복귀·C 수명·추가 GPS BLOCKED. [최초 G9](recovery-ui-physical-g9.md) · [재시험](recovery-ui-physical-g9-followup.md) |
| G10 공개 자료 | PASS | 최종 원본 PNG, 이전 이력, 상대 링크 및 개인정보 노출 검사. |
| G11 최종 원격 CI | [최종 head 판정](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9/checks) | PR과 push의 `build-and-test`·`live-api36`. |

실기기 G7·G8·G9가 INCONCLUSIVE로 남아 Draft 상태다. 프로젝트 소유자의 잔여 위험 수용과 `main` 병합 승인은 모두 미완료다. [판정의 적용 범위](recovery-ui-final-acceptance.md)

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

API 36의 오프라인·시나리오·재생·지도 전환 계측 13개, 실제 상태별 캡처 계측 1개, 320×640·density 160·ko-KR·글꼴 130% 야간 지도와 조작 접근 계측 1개, TalkBack이 실제 활성화된 접근성 계측 1개가 통과했다. 후보의 화면에서 OL07 정상·추정·중단·재획득·복구와 결과, OL02 위치 점프 거부, OL10 모호성, OL13 이탈·복귀를 확인했다.

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

API 26의 초기 후보에서 `UiDevice.pressHome()`이 false를 반환한 뒤 실제 런처가 화면에 표시됐다. 런처 노드로 확인하도록 수정한 계측은 로컬 API 26을 통과했으나 GitHub의 API 36 x86_64에서는 키 입력 성공 뒤에도 해당 노드를 찾지 못했다. 최종 계측은 Home 입력 뒤 Live Activity의 `onStop`에 해당하는 수명 주기와 창 포커스 해제를 확인한다. 홈·화면 OFF에서 FGS와 리스너가 유지되고 복귀 후 같은 토큰을 사용하며 Stop에서 자원이 해제되는지 검사한다. 이 보완은 계측 APK에만 적용했다.

## Phase 9 · 대표 화면과 문서

최종 앱 UI 소스 `3e35cf85446d9a2fc8f0079232ea58a0197e43ad`의 Debug APK를 API 36 ARM64 에뮬레이터에서 실행했다. 공개 PNG는 1080×2400, density 420, en-US, 글꼴 100%, Day와 기본 Follow로 촬영했다. `RecoveryUiCaptureTest`가 실제 앱의 Step을 반복 실행했고 별도 재생 결과로 상태와 관측 이벤트를 확인했다. 최종 후보와 기준선의 시나리오·가상 시각·신호·위치 출처·경로·실제 reason code 기록은 동일했다.

| 원본 | 가상 시각 | 관측 상태 및 판정 |
|---|---:|---|
| [OL07 정상](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol07-tracking.png) | 28,000ms | TRACKING · MAP_MATCHED · ON_ROUTE |
| [OL07 추정](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol07-estimating.png) | 54,000ms | LOST · DEAD_RECKONED · UNKNOWN. Fix 경과 6초, 추정 상한 9초 남음 |
| [OL07 중단](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol07-last-known.png) | 63,000ms | LOST · LAST_KNOWN · `DR_LIMIT` |
| [OL07 복구](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol07-recovered.png) | 73,000ms | TRACKING · MAP_MATCHED · ON_ROUTE · `SIGNAL_RECOVERED` |
| [OL07 결과](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol07-complete-results.png) | 120,000ms | `RUN_COMPLETED`, 해당 가상 실행 평가 |
| [OL02 관측 거부](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol02-jump.png) | 32,000ms | DEGRADED · `IMPLAUSIBLE_MOVE` |
| [OL10 모호성](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol10-ambiguous.png) | 0ms | TRACKING · OBSERVED · UNKNOWN · `AMBIGUOUS` |
| [OL13 이탈](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol13-departure.png) | 44,000ms | TRACKING · MAP_MATCHED · OFF_ROUTE · `REROUTE_RECOMMENDED` |
| [OL13 복귀](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo/recovery-ui/ol13-return.png) | 67,000ms | TRACKING · MAP_MATCHED · ON_ROUTE · `ROUTE_RETURNED` |

README의 대표 화면 세 장과 데모를 위의 실제 캡처로 갱신했다. 국내 OSM 갤러리와 이전 검증 결과는 각 시기 기록에서 참조한다. 공개 원본 9장은 가상 주행이며 Live 화면은 `FLAG_SECURE`를 적용한 상태에서 계측으로 검수했다. [당시 데모](https://github.com/CodingBot000/AndroidLocaltionRecovery/blob/8c572cd1d5e8c1378834310bccab43b058f16e4a/docs/demo.md)

## Phase 10 · 변경 검토와 PR

`codex/recovery-ui-refactoring`에서 단계별 커밋을 검토하고 `main`을 대상으로 초안 PR을 제출했다. 기준선과의 diff에서 위치·지도 엔진, fixture와 정답, 앱·Live manifest, Live 서비스, 빌드·CI 설정이 동일함을 확인했다. 새 기록과 캡처를 포함한 Markdown 29개에서 상대 링크와 앵커를 검사했다. 초기 PR CI의 `live-api36` 실패와 계측 보완은 Phase 8에 기록했다. 최종 head의 CI 결과와 머지 상태는 PR에서 확인할 수 있다. 전환 성능 판정과 물리 단말 검증이 남아 있다. [PR #9](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9) · [GitHub Actions 검사](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9/checks)
