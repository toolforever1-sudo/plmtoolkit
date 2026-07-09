package com.sandisk.plm.tracker.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the prod-vs-nonprod detection that drives the QA banner wording.
 */
public class InstanceEnvServiceTest {

    private static InstanceEnvService withJdbc(String jdbc) {
        InstanceEnvService s = new InstanceEnvService();
        setField(s, "jdbcUrl", jdbc);
        return s;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = InstanceEnvService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void prodDbHostIsProd() {
        assertTrue(withJdbc("jdbc:oracle:thin:@uls-dp-oraagile.wdc.com:1521:agprod").isDbProd());
    }

    @Test
    void prodSidIsProd() {
        // host masked but SID still prod
        assertTrue(withJdbc("jdbc:oracle:thin:@somehost.wdc.com:1521:agprod").isDbProd());
    }

    @Test
    void qaDbIsNotProd() {
        assertFalse(withJdbc("jdbc:oracle:thin:@uls-dq-oraagile.wdc.com:1521:agqa").isDbProd());
    }

    @Test
    void emptyJdbcIsNotProd() {
        assertFalse(withJdbc("").isDbProd());
        assertFalse(withJdbc(null).isDbProd());
    }

    @Test
    void prodDataTrueWhenDbProdEvenIfAgileNonProd() {
        InstanceEnvService s = new InstanceEnvService() {
            @Override boolean isAgileProd() { return false; }
        };
        setField(s, "jdbcUrl", "jdbc:oracle:thin:@uls-dp-oraagile.wdc.com:1521:agprod");
        assertTrue(s.isProdData(), "prod DB alone must keep prodData true");
    }

    @Test
    void prodDataTrueWhenAgileProdEvenIfDbNonProd() {
        InstanceEnvService s = new InstanceEnvService() {
            @Override boolean isAgileProd() { return true; }
        };
        setField(s, "jdbcUrl", "jdbc:oracle:thin:@uls-dq-oraagile.wdc.com:1521:agqa");
        assertTrue(s.isProdData(), "prod Agile alone must keep prodData true");
    }

    @Test
    void prodDataFalseOnlyWhenBothNonProd() {
        InstanceEnvService s = new InstanceEnvService() {
            @Override boolean isAgileProd() { return false; }
        };
        setField(s, "jdbcUrl", "jdbc:oracle:thin:@uls-dq-oraagile.wdc.com:1521:agqa");
        assertFalse(s.isProdData(), "both non-prod -> prodData false");
    }
}
