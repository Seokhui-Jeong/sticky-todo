# 배포용 빌드에서 코드를 줄일 때(R8) 지켜야 할 것들.
# 화면·위젯·백그라운드 작업 클래스는 AndroidManifest 와 라이브러리 규칙이 이미 지켜 준다.
# (WorkManager 는 SyncWorker 의 생성자를, 구글 로그인은 자기 클래스들을 스스로 보호한다)

# 디버깅용 로그 호출은 통째로 지운다
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# 예외가 났을 때 줄 번호라도 남도록 (크기는 거의 늘지 않는다)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
