# Android 지도 렌더링·메모리 면접 노트

## 렌더링

- **TTID와 TTFD**는 각각 첫 화면까지 걸린 시간과 앱이 준비 완료를 보고할 때까지 걸린 시간이다. O10 콜드 기동에서 TTFD는 지도 준비 흐름과 `reportFullyDrawn()`에 연결된다. 전환 시험은 별도로 O07 준비까지를 재고, 프레임 측정은 O10 복귀까지 포함한다.
- **Frame deadline**은 각 프레임이 목표한 표시 시한이다. `frameOverrunMs`의 양수는 시한 초과, 음수는 남은 시간이다. **P95**는 느린 쪽의 꼬리를 보여준다. 여러 반복의 프레임을 합한 P95와 반복별 P95의 중앙값은 질문이 다르다. 표본이 적거나 Samsung trace에서 종료 시각이 빠진 경우 프레임 수와 함께 말한다.
- **Composition, layout, draw**는 UI 트리 갱신, 크기·위치 결정, 그리기 명령 기록 단계다. `RoadMap`의 정적 지도·오버레이·라벨은 같은 카메라를 따르지만 읽는 상태는 서로 다르다. 카메라가 고정되어도 상위 노드가 다시 그려지면 정적 Canvas의 호출 횟수가 달라질 수 있다.
- **UI thread와 RenderThread**에서는 각각 Compose, 레이아웃 및 명령 기록과 HWUI 렌더링 작업을 관찰한다. GPU 완료 대기와 **SurfaceFlinger**의 합성·표시 기록은 별도 관측이다. **FrameTimeline**에서는 앱 프로세스, surface frame token과 display frame token으로 이 구간을 연결한다. `sched`의 실행 시간, 실행 가능 상태에서의 대기, `doFrame` 벽시계 시간은 각각 따로 읽는다.
- **Viewport culling**은 현재 화면과 교차하는 도형을 고르는 일이고, **LOD**는 카메라의 거리와 모드에 따라 표시할 세부 수준을 결정하는 일이다. 방향을 바꾸거나 확대했을 때 항목 순서, 라벨 충돌, 터널·교량과 경로 표현도 맞아야 한다.
- **캐시 소유권과 무효화**는 어느 객체가 자원을 들고 있으며 어떤 입력이 바뀔 때 다시 계산하는지를 설명한다. 정적 `WorldPath`는 지도 월드에 속하고, 계획 경로는 `MapGeometry`에 속한다. `MapLabelCache`는 정확한 projection·LOD·geometry의 조합으로 투영 결과를 식별한다.

## 메모리

- **Java 사용 힙**은 Java/Kotlin 객체의 사용량을 보는 지표다. **RSS**는 프로세스에 매핑된 상주 페이지의 규모이고, **PSS**는 공유 페이지를 비례 배분한다. **Graphics summary**는 Android 메모리 보고 범주다. 같은 프로세스에서 첫 준비, 완료, 초기화, 유휴 시점의 값을 따로 비교한다.
- **Path**는 Java/Kotlin에서 보이는 래퍼와 내부 그래픽 할당을 가질 수 있다. 첫 표시 때 래퍼와 GPU/HWUI 캐시가 각각 늘 수 있다. Java 참조 그래프, 네이티브 할당의 호출 스택, PSS를 맞춰 해석한다. SM-G975N의 API 31 ART heap graph에서 Java 객체별 native size는 제공되지 않는다. [Perfetto ART Heap Dumps](https://perfetto.dev/docs/data-sources/java-heap-profiler)
- **GC root**는 추적의 시작점이고, **retained size**는 한 객체가 더 이상 도달 가능하지 않을 때 함께 해제될 수 있는 크기다. 여러 경로에서 가리키는 공유 그래프는 dominator로 구분한다. O10 반복에서는 끝난 Activity, 이전 시나리오, 캐시 및 Job이 실제로 남아 있는지 경로를 확인한다.
- **Allocation churn**은 짧게 살다 사라지는 객체를 반복해서 만드는 현상이다. **누수**는 수명이 끝나야 할 객체가 여전히 루트에서 도달 가능한 상태로 남는 경우다. PSS 상승에는 그래픽 캐시나 할당자가 보관하는 페이지도 기여할 수 있다.

## 실험 설계

같은 단말, APK 빌드 유형, 컴파일 모드, fixture, 사용자 조작으로 기준선과 후보를 교차 측정한다. 라벨을 잠시 제외하는 격리 실험은 라벨의 기여도를 살피는 용도다. 채택 후보에는 같은 라벨과 카메라를 유지하며 초기 기동, 느린 프레임, 30분 메모리 경향과 저사양 렌더링을 함께 확인한다. 과거 비교의 Follow 복귀 축척과 최신 코드의 축척 보존 동작은 차이가 있어, 이번 A/B의 기준은 현재 `main`으로 고정한다.

[Compose 성능 단계](https://developer.android.com/develop/ui/compose/performance/phases) · [Macrobenchmark 지표](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) · [FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline) · [네이티브 할당 프로파일](https://perfetto.dev/docs/data-sources/native-heap-profiler)
