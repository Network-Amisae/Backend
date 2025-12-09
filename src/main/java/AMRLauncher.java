public class AMRLauncher {
    public static void main(String[] args) {
        System.out.println(">> AMR 시스템을 가동합니다...");

        // 1. AMR_01 실행 (별도 스레드)
        new Thread(() -> {
            new AMRClient("AMR_01").start();
        }).start();

        // 실행 텀 (로그 가독성 위해 1초 딜레이)
        try { Thread.sleep(1000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 2. AMR_02 실행 (별도 스레드)
        new Thread(() -> {
            new AMRClient("AMR_02").start();
        }).start();

        System.out.println(">> 모든 AMR이 기동되었습니다.");
    }
}
