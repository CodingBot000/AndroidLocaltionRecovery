# PR #9 실기기 GPS·Live 서비스 검증

이 문서는 최초 시도를 기록한다. 사용자가 잠금을 해제한 뒤 시작한 별도 재시험은 [G9 미완료 항목 재시험](recovery-ui-physical-g9-followup.md)에 기록했다.

## 범위와 조건

2026-11-27 KST에 SM-G975N(Android 12/API 31, Exynos 9820, ARM64)에서 기준 A `a732af2dddfb86514fbfa76a6d7971f31e8d23da`와 최종 제품 C `ce617de21ffbf5485564c9d7bc589a3b6085c02c`를 비교했다. 시작 시 [PR #9](https://github.com/CodingBot000/AndroidLocaltionRecovery/pull/9)는 `bb271ed356037585cc8f9106cb8de1d464a388da`였다. 격리된 두 worktree에서 계측 APK의 관측만 보완했다. 실기기에 설치한 A/C 제품 Debug APK는 G8에서 사용한 제품 APK와 바이트 단위로 일치했다.

실기기는 USB 충전, 1,080×2,280, density 420, 약 60Hz, 글꼴 1.1, `ko-KR,en-US`였다. 위치 스위치 ON, 비행기 모드 OFF, Wi-Fi ON과 보안 잠금 설정을 유지했다. 사용자가 승인한 Live 정밀·대략 위치 권한만 시험 동안 부여했다. 위치를 식별하는 좌표 대신 권한·provider·fresh 여부·콜백 수·GNSS 상태와 세션 조건을 기록했다. Live 화면은 실제 `FLAG_SECURE` 설정을 계측으로 확인했다.

## 검증 계약

- 수신은 원래 `LiveRealDeviceSmokeTest`의 Start 이후 `gps` provider, non-mock 및 단조 측정 시각의 fresh 조건과 120초 제한을 사용했다. 앱의 FGS와 현재 GPS 리스너를 확인했다. 실제 fix를 받으면 출처·정확도·수신/처리/드롭 등의 화면 표시를 같은 Live 상태와 대조하도록 계측에 단언을 추가했다.
- 수신 실패 시 `PhysicalLocationDiagnosticsTest.observesLiveAndDirectGpsSequentially`를 사용했다. Live에서 GNSS 상태를 관찰한 뒤 Stop과 등록 해제를 확인하고 직접 `GPS_PROVIDER`에 1초·0m로 요청한다. 두 요청을 각각 최대 120초 관찰했다.
- 수명은 `retainsPhysicalSessionAcrossRecreationHomeAndScreenOff`의 Activity 재생성 3회, Home, 화면 OFF 300초, 기존 세션 토큰·FGS 및 현재 리스너 유지와 UI Stop 해제를 사용했다. 잠금 화면이 나타나면 기존 90초 제한 안에서 사용자가 직접 해제하는 방식이다.

## 수신과 직접 provider 대조

| 관측 | A | C |
|---|---|---|
| 원래 120초 fresh 수신 | 미수신. ACTIVE, FGS 및 리스너 등록 확인 | 미수신. ACTIVE, FGS 및 리스너 등록 확인 |
| Live 읽기 상태와 처리 | `WAITING`, 수신 0·처리 0·드롭 0 | `WAITING`, 수신 0·처리 0·드롭 0 |
| Live 진단 GNSS 상태 이벤트 / 최대 관측 / fix 사용 | 122 / 0 / 0 | 120 / 0 / 0 |
| 직접 GPS 콜백 / fresh | 0 / 0 | 0 / 0 |
| 직접 요청 GNSS 상태 이벤트 / 최대 관측 / fix 사용 | 121 / 0 / 0 | 120 / 0 / 0 |
| 각 수신 단계 종료 후 FGS / 현재 GPS 리스너 | 0 / 0 | 0 / 0 |

직접 GPS 요청에서도 위성과 fix가 관측되지 않았다. 이번 조건에서는 앱을 거치지 않는 요청도 동일한 무수신이었으므로 C 고유의 위치 전달 문제를 확인할 수 없었다. GNSS가 위성을 관측하지 못한 구체적 원인은 확정하지 못했다. 실제 fix가 필요한 출처·정확도·지도 영역 안팎 대응은 검증되지 않았다.

## Live 대기 UI

A와 C에서 각각 별도의 짧은 계측으로 `ACTIVE`·정밀 위치·`WAITING` 표시를 같은 `LiveSessions` 상태와 대조했다. 실제 수신·처리·드롭 0을 화면 숫자와 비교했고, fix가 없는 동안 위치 출처 줄이 보이지 않는 것도 확인했다. 두 계측은 `FLAG_SECURE`와 종료 뒤 상태·FGS·리스너 해제를 단언했다. 이 결과는 무수신 상태의 UI 정합성에 해당한다.

## 화면 OFF와 세션 유지

A의 첫 수명 계측에서 Activity를 3회 재생성한 뒤 Home으로 나갔다. 화면 OFF **303.979초** 동안 폴링마다 같은 세션 토큰·FGS·현재 GPS 리스너를 확인했고 콜백은 0건이었다. Wake 뒤 잠금 해제 안내를 보냈으나 기존 90초 제한에서 해제되지 않아 전면 복귀와 UI Stop까지 이어지지 않았다. 이 시도는 사용자 잠금 대기에서 시간 초과됐다. 실패 경로에서 서비스 정리 후 FGS와 현재 GPS 리스너는 각각 0개였고 두 임시 위치 권한을 회수했다.

C의 수명은 시작 전 보안 잠금이 유지돼 **BLOCKED**였다. C의 수신·대기 UI 시험이 종료될 때의 FGS와 현재 GPS 리스너는 각각 0개였다. A의 잠금 복귀·UI Stop과 C의 화면 OFF·복귀는 이번 증거에 포함되지 않는다.

## 판정과 복원

**G9 종합 판정은 INCONCLUSIVE다.** 두 제품의 무수신이 직접 GPS 요청에서도 관찰됐고 실제 fix의 화면 정합성을 확인할 수 없었다. 대기 UI와 실행된 서비스 해제는 확인했다. A의 5분 화면 OFF 유지와 재생성은 관찰했으며 사용자 잠금 해제가 필요한 후속 범위는 BLOCKED다. G3·G7·G8은 **INCONCLUSIVE** 판정을 유지했다.

설치 APK 6개와 기존 계측 미디어 13개의 SHA-256, 앱 데이터 디렉터리와 패키지 중지 상태, Live 중단 상태 파일, 정밀·대략 위치 권한과 플래그가 시험 전과 일치했다. 추적한 화면·네트워크 설정과 위치 스위치, 보안 잠금 설정도 일치했다. 화면을 깨웠지만 잠금 화면이 남아 있어 시험 전의 잠금 해제된 홈 상태 복원은 사용자 해제를 기다린다. 이번 시험의 단말 임시 파일은 로컬 사본과 해시를 비교한 뒤 제거했다.

## 자료와 재현

실제 AndroidJUnitRunner 출력과 허용한 두 태그의 원본 로그, APK·계측 패치 해시, 환경 및 복원 기록은 로컬 Git 제외 경로 `build/reports/recovery-ui/pr9-g9-physical-20261127/`에 보존했다. [익명화 관측과 해시](benchmarks/sm-g975n-pr9-g9.json)

정확한 A/C 소스의 제품 Debug APK와 해당 계측 APK를 사용한다. 실제 수신 시험의 등록·해제와 실패 시 GNSS 대조는 아래 기존 메서드를 선택한다. 수명 재현은 잠금 해제가 가능한 사용자가 있는 시점에 실행한다.

```bash
adb -d shell am instrument -w -r -e realLocation true \
  -e class 'lab.recovery.live.LiveRealDeviceSmokeTest#startsReceivesFreshGpsAndStops' \
  lab.recovery.live.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument -w -r -e realLocation true \
  -e class 'lab.recovery.live.PhysicalLocationDiagnosticsTest#observesLiveAndDirectGpsSequentially' \
  lab.recovery.live.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument -w -r -e realLocation true \
  -e class 'lab.recovery.live.PhysicalLocationDiagnosticsTest#retainsPhysicalSessionAcrossRecreationHomeAndScreenOff' \
  lab.recovery.live.test/androidx.test.runner.AndroidJUnitRunner
```
