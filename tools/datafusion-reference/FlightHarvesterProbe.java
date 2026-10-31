import dev.brikk.house.sql.intellij.catalog.*;
import java.sql.*;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Runs the production Kotlin harvesters over real JDBC rows, in the probe JVM. */
public class FlightHarvesterProbe {
    static class Rows implements CatalogRowSource, CatalogServerInfoSource {
        final Connection connection;
        Rows(Connection connection) { this.connection = connection; }
        public CatalogServerInfo serverInfo() {
            try { var m=connection.getMetaData(); return new CatalogServerInfo(m.getDatabaseProductName(), m.getDatabaseProductVersion()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        }
        public void forEachRow(String sql, Function1<? super CatalogRow, Unit> consume) {
            try (Statement s=connection.createStatement()) {
                s.setQueryTimeout(20);
                try (ResultSet r=s.executeQuery(sql)) {
                    while (r.next()) consume.invoke((CatalogRow) column -> { try { return r.getString(column); } catch (SQLException e) { throw new RuntimeException(e); } });
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }
    public static Object harvest(Connection connection, String product) {
        LiveCatalogHarvester harvester = product.equals("spice") ? new SpiceCatalogHarvester() : new DatafusionCatalogHarvester();
        Harvest result = Objects.requireNonNull(harvester.harvest(new Rows(connection)), "incomplete production harvest");
        LiveInventory inventory = result.getShared();
        if (inventory.getSignatures() || inventory.getFunctions().stream().anyMatch(f -> !f.getDef().getOverloads().isEmpty()))
            throw new AssertionError("representative samples became exhaustive signatures");
        NativeCatalog catalog = Objects.requireNonNull(inventory.getNativeCatalog());
        if (catalog.getRelations().stream().noneMatch(r -> r.getPath().getLast().equals("a.b"))) throw new AssertionError("dotted name lost");
        if (product.equals("spice") && inventory.getFunctions().stream().filter(f -> f.getDef().getName().equals("vector_search")).count() != 2)
            throw new AssertionError("scalar/table identity lost");
        return Map.of("product", catalog.getProduct(), "version", inventory.getServerVersion(),
            "functions", inventory.getFunctions().size(), "relations", catalog.getRelations().size(),
            "signature_policy", "unknown", "revision", catalog.getRevision());
    }
}
