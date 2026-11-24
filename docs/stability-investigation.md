# Android 장애와 자원 조사

검증일은 2026-11-25(KST)다. 프로세스 장애는 폐기 가능한 ARM64 에뮬레이터에서 수행했다. 저장소에는 좌표·PID·기기 식별자·스택 전문·heap 원본 대신 집계 결과만 남겼다.

## 코드에서 확인한 문제

| 현상과 조건 | 원인과 변경 | 재검증 |
|---|---|---|
| 장시간 Live 입력을 기존 엔진으로 연결하면 처리한 관측 ID가 계속 남을 수 있었다. | 기존 엔진은 재생 동안 ID 집합을 유지한다. Live 입력은 어댑터가 새 토큰과 함께 발급한 증가 sequence로 중복을 판단한다. 기존 재생의 임의 ID 검증은 유지한다. | 기존 재생 trace·평가 해시, Live 시간 역전·동일 시각·영역 재진입, 정지 후 해제 검증. |
| 대략적 권한 세션에서 오래된 첫 fix가 도착하면 `Stale fix`만 표시됐다. | fix 상태와 Android 권한 모드를 같은 행으로 표시했다. Live 화면에 권한 모드와 fix 상태를 각각 표시한다. | API 36에서 시스템 권한 거부·대략적 권한 수락·원시 진단 및 종료를 재실행했다. |
| API 26에서 기존 앱의 기본 O10 화면은 48MiB 앱 힙에서 기동 중 OOM이 발생했다. 같은 조건의 Live는 대형 지도 준비 오류를 표시하고 세션을 해제했다. | 기존 폴리곤·도로가 포함된 O10 JSON을 Gson 객체 트리로 먼저 읽는 경로에 일시적인 대형 할당이 있다. APK의 JSON은 원본과 일치한다. | 256MiB로 설정한 별도의 API 26 계측 환경에서는 O10과 Live 20회 반복이 통과했다. 원래 48MiB 조건은 미해결이며 일반 릴리스 힙 정책은 동일하다. |
| 실험 클라이언트에서 503과 `Retry-After: 0`가 만나면 OkHttp가 호출 중 follow-up을 수행할 수 있었다. | 각 전송 시도에서 one-shot 요청 바디를 사용하고 재시도 횟수와 기한을 coordinator가 관리한다. | N04 실제 요청 2건·coordinator 시도 2회·경로 반영 1회. |

## A01–A06 증거

| ID | 실제 관측 |
|---|---|
| A01 | API 36의 Debug 전용 `:fault` 프로세스에서 UI 입력 처리 스레드를 약 7.5초 멈췄다. 추가 입력을 보내자 이벤트 버퍼의 `am_anr`와 `dumpsys activity lastanr`에 MotionEvent 대기 5,003ms가 기록됐다. |
| A02 | API 34 `atrace`에서 FGS 승격 23건이 앱 main 스레드에서 끝난 후 지도 읽기·파싱·인덱스 준비 23건이 작업 스레드에서 수행됐다. 구간 중앙값은 각각 2.410ms와 276.524ms다. |
| A03 | 위치 등록·해제 20회와 화면 재생성, 지연 HTTP 취소, 늦은 A 응답 무효화를 검증했다. 격리 worktree에서 generation 비교, listener 해제 및 방향 전이 검증을 각각 제거하자 해당 테스트가 실패했고, 복원한 소스의 테스트는 통과했다. |
| A04 | `:fault`의 의도적 예외가 `ApplicationExitInfo`에서 APP CRASH(EXCEPTION)으로 기록됐다. 일반 Live에서 시스템 앱 정지, force-stop, SIGKILL 및 재부팅 후 자동 재시작 없이 중단을 표시했고 명시적 새 세션을 시작했다. |
| A05 | API 34 디버그 앱에서 GC 후 heap을 열어 활성·정지 상태를 비교했다. 각 1개였던 서비스·위치 어댑터·등록·세션·지도 인스턴스는 정지 뒤 0개였다. 같은 Activity는 양쪽에 1개였다. 두 지도에서 세션을 20회 시작·정지하고 시스템 listener 해제를 확인했다. |
| A06 | API 34 오프라인 재생·지도 전환 20회와 Activity 재생성 4회, 1× 180초 주행 및 JVM HTTP 취소 20회를 실행했다. Live callback FIFO는 16개이며 오래된 세션 콜백을 폐기한다. HTTP 취소 뒤 실행·대기 호출 수는 0이었다. |

ANR과 Crash는 위 하네스의 의도적 재현이다. 이 실행에서 일반 앱의 ANR 또는 Crash가 관측됐다는 뜻은 아니다. PSS는 Java 객체 보유 여부와 별개의 시점별 지표다. 단일 heap 비교 및 반복 시험의 범위 밖에서는 장기 누수 여부를 판정할 수 없다.

## 재현

`tools/run_live_instrumentation.sh`는 폐기 가능한 에뮬레이터에서 API 26 기본 lifecycle, API 30+ mock provider 경로를 실행한다. `tools/check_live_recovery.py`는 API 33+ 에뮬레이터의 Live 테스트 설치를 초기화한 뒤 실제 권한 UI와 프로세스 종료 조합을 확인한다. 일부 검증을 골라 실행할 때 `--phase`를 지정할 수 있다.

```bash
./gradlew :live:assembleDebug :live:assembleDebugAndroidTest :app:assembleDebugAndroidTest
tools/run_live_instrumentation.sh
python3 -B tools/check_live_recovery.py
adb shell atrace --async_start -b 12000 -a lab.recovery.live sched am wm view
tools/run_live_instrumentation.sh
adb shell atrace --async_stop > live-trace.txt
```

별도 Debug 하네스는 에뮬레이터와 Android의 `DUMP` 권한을 가진 실행자에서만 열린다. `adb shell am start -n lab.recovery.live/.FaultHarnessActivity --es fault crash`로 의도적 예외를 재현했다. `--es fault block`은 단일 버튼을 약 7.5초 동안 차단한다. 동작 중 동일 위치에 두 번째 입력을 제공하고 시스템의 `am_anr`와 `dumpsys activity lastanr`를 검사했다. 실험 후 `adb shell am force-stop lab.recovery.live`로 정리한다.

API 30 이상에서 Live 화면은 자체 앱 종료 사유·시간·importance의 최근 5개 메타데이터 중 최신 항목을 표시한다. Debug의 `fault inspect`는 최대 65,537바이트를 읽으며 ANR이 발생한 프로세스를 정지한 뒤 OS 제공 스트림에서 43,963바이트를 읽었다. 원본 본문은 기록하지 않는다. API 26–29는 OS 종료 이력 API를 제공하지 않는다.

공식 Android 동작을 2026-11-25에 확인했다. [ANR 진단](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs) · [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)

[수용 게이트와 성능 원본](verification.md)
