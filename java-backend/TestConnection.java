import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class TestConnection {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://127.0.0.1:5433/gitOracle";
        String user = "gitOracle";
        String password = "GitOracle_PG_2025";
        
        try {
            System.out.println("Connecting to " + url);
            Connection conn = DriverManager.getConnection(url, user, password);
            System.out.println("Success!");
            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
