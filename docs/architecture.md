# 아키텍처

```mermaid
flowchart TD
    A[":app · Activity / asset adapter"] --> F[":feature:lab · ViewModel / Compose"]
    A --> S[":core:simulation · catalog / replay / evaluation"]
    F --> U[":ui:map · Compose map / camera"]
    F --> S
    F --> L[":core:location · tracking engine"]
    F --> M[":core:map · geometry / network / route"]
    U --> M
    S --> L
    S --> M
    L --> M
    B[":benchmark · Macrobenchmark"] -. target APK .-> A
```

`verifyModuleBoundaries`는 Gradle 구성에서 7개 모듈의 선언 의존성을 검사한다. Android 테스트의 도메인 타입과 Macrobenchmark의 대상 APK는 테스트 경계로 다룬다. 세 코어 모듈은 JVM에서 독립 실행된다.

```mermaid
flowchart LR
    D[Fixture source] --> R[ScenarioRepository]
    R --> C[ReplayController]
    C --> S[ReplaySession]
    S -->|Observation / Tick| E[TrackingEngine]
    E -->|Snapshot / events| S
    S -->|Full decisions and ticks| V[Evaluator]
    R -->|Ground truth / expectations| V
    V --> C
    C -->|Bounded presentation state| F[LabViewModel]
    F -->|Scene and display overlay| U[RoadMap]
    F -->|User intents| C
```

## 재생과 취소

- 앱은 asset 어댑터를 주입한다. JVM CLI에는 파일 어댑터를 주입한다. 두 환경에서 원본 JSON과 검증 로직을 공유한다.
- `ReplaySession`이 가상 시각별 관측·Tick·종료를 결정적으로 처리한다. 모든 이벤트는 바로 평가기에 기록된다. `ReplayController`는 단일 명령 큐와 현재 실행 ID로 데이터 로딩, 엔진, 완료 결과를 조율한다. 무거운 단계에는 IO·작업 Dispatcher를 사용한다.
- 재생 deadline은 단조 시계와 하나의 가상 시간 기준점에서 계산한다. Pause는 가상 진행량을 동결하고 배속 변경은 변경 시각까지 진행된 양을 반영한다. 지연 발생 시 최대 12개 가상 시각을 한 차례 처리한 뒤 다음 명령을 받을 수 있다. 256칸 입력 큐가 가득 차면 화면에 재시도 상태를 표시한다.
- 화면 로그는 500건, 표시 이력은 출처별 1,000개다. 이력은 추정 시작·중단, 위치 거부·재정합, 출처 변경에서 끊긴다. 세션 전환은 이전 timer generation과 실행 ID를 무효화한다. 세로 Activity 재생성은 같은 ViewModel을 사용하고, 제거 시 컨트롤러 scope를 취소한다. 기존 백그라운드 동작에 따라 ViewModel 생존 동안 재생을 이어간다.

## 지도

`MapDefinition`은 방향성 네트워크와 UI 표현 데이터를 가진다. `RoutePlan`은 시나리오마다 연결하며 같은 지도에 서로 다른 계획을 붙일 수 있다. 엔진은 노드 ID와 허용 전이 및 시나리오 계획 경로를 사용한다. 건물·수변·라벨과 기대 판정은 엔진 입력에 포함되지 않는다.

`ui:map`은 지형·도로·경로 Path를 활성 장면 한 개에 대해 월드 좌표로 준비한다. 차량 데이터가 갱신되면 카메라 행렬로 경로와 지형을 투영하고 동적 오버레이를 그린다. Follow는 채택·추정·마지막 확인 위치를 사용한다. Overview는 계획 경로 bounds에 맞추고 수동 pan·zoom은 Free 모드로 전환한다. 이 상태는 지도 Composable이 소유한다. 카메라 조작과 지도 색상 변경은 재생 명령을 보내지 않는다.

`benchmark`는 Release 상속 변형의 별도 패키지 `lab.recovery.app.benchmark`를 대상으로 한다. 콜드 스타트, 명시적으로 선택한 15초 DEMO, M10의 첫 45초 프레임 계측을 구성한다.


## 지도 품질 개정

지도 v2 표현 개정은 방향을 지정한 필지·건물, 구역 등급, 수변·녹지, 산책로와 이름을 읽는다. 기존 도로·경로·전이 식별자의 해시를 검사하고 시나리오 원본을 공유한다. `MapGeometry`가 월드 Path와 bounds를 준비하고, `MapLayers`가 가시 영역에 해당하는 지형·제어된 도로 레이어·경로와 오버레이를 그린다. `MapLabels`는 화면 좌표에서 라벨 방향·경계·우선순위를 계산한다. [지도 디자인](map-design.md)


## OSM 오프라인 데이터셋

`ScenarioRepository`는 합성 v2와 OSM v3 카탈로그를 별도로 읽는다. OSM 기본 데모는 OSM 카탈로그의 representative 항목에서 선택한다. OSM 지도는 출처·투영·원점·표시 경계와 시나리오에 고정된 SHA-256을 보유한다. 개발용 PBF 변환은 일반 빌드와 Android에서 실행하지 않는다.

`ui:map`은 맵 모듈로 전달된 실제 좌표, 내곽이 있는 다각형, 방향별 에지의 물리 도로 ID, 로딩된 지도 경계를 사용한다. 화면 밖 world Path는 그리기에서 제외하며 한 장면의 Path는 가시화될 때 만들어 재사용한다. 출처 버튼은 지도 카메라와 별도 좌표로 배치한다. [OSM 데이터 파이프라인](osm-data-pipeline.md)


국내 지도 확장에서는 표현용 도형에 철도·공공 시설·POI와 축척별 도형을 추가했다. 동일 폴리라인을 공유하되, 화면 인덱스와 단순화가 도로 매칭이나 가상 주행에 영향을 주지 않는다. [국내 상세 지도 설계](korea-map-design.md)
