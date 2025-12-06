public class FactoryLauncher {
    public static void main(String[] args) {
        System.out.println(">> 스마트 팩토리 셀(Cell) 시스템을 가동합니다...");

        // Cell 1~4 동시 실행
        new CellClient("CELL_01").start();
        try { Thread.sleep(500); } catch (Exception e) {} // 순차 실행 느낌 주기

        new CellClient("CELL_02").start();
        try { Thread.sleep(500); } catch (Exception e) {}

        new CellClient("CELL_03").start();
        try { Thread.sleep(500); } catch (Exception e) {}

        new CellClient("CELL_04").start();

        System.out.println(">> 모든 셀이 ACS 서버에 접속했습니다.");
    }
}