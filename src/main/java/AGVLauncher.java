public class AGVLauncher {
    public static void main(String[] args) {
        System.out.println(">> AGV 시스템을 가동합니다...");

        // 1. AGV_01 실행 (별도 스레드)
        new Thread(() -> {
            new AGVClient("AGV_01").start();
        }).start();

        // 1초 뒤에 두 번째 AGV 실행 (시간차를 두면 로그 보기가 편함)
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        // 2. AGV_02 실행 (별도 스레드)
        new Thread(() -> {
            new AGVClient("AGV_02").start();
        }).start();

        System.out.println(">> 모든 AGV가 기동되었습니다.");
    }
}
