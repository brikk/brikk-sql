import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
class NativeSemanticProbe {
    static String encode(String s) {return s==null?"-":Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    public static void main(String[] args)throws Exception {
        Properties p=new Properties();p.setProperty("enable_external_access","false");p.setProperty("autoload_known_extensions","false");p.setProperty("autoinstall_known_extensions","false");p.setProperty("threads","1");
        try(Connection c=DriverManager.getConnection("jdbc:duckdb:",p)) {
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA version")) {r.next();System.out.println("ENGINE\t"+encode(r.getString(1))+"\t"+encode(r.getString(2)));}
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT current_setting('TimeZone'),current_setting('error_on_division_by_zero'),current_setting('regex_match_operator_semantics')")) {
                r.next();System.out.println("SETTINGS\t"+encode(r.getString(1))+"\t"+encode(r.getString(2))+"\t"+encode(r.getString(3)));
            }
            for(String line:Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8)) {
                String[] f=line.split("\t",-1);String sql=new String(Base64.getDecoder().decode(f[1]),StandardCharsets.UTF_8);
                try(Statement s=c.createStatement()) {
                    s.setQueryTimeout(10);
                    try(ResultSet r=s.executeQuery(sql)) {
                        if(!r.next())throw new SQLException("No result row");
                        String a=r.getString(1),b=r.getString(2),n=r.getString(3);
                        if(r.next())throw new SQLException("More than one result row");
                        System.out.println(f[0]+"\tok\t"+encode(a)+"\t"+encode(b)+"\t"+encode(n));
                    }
                }catch(SQLException e){System.out.println(f[0]+"\tengine_error\t"+encode(e.getSQLState())+"\t"+encode(e.getMessage())+"\t-");}
            }
        }
    }
}
