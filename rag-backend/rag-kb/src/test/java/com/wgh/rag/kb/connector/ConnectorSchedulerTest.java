package com.wgh.rag.kb.connector;

import com.wgh.rag.kb.entity.KbConnector;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时同步触发条件：启用、间隔、上次同步时间。
 */
class ConnectorSchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 12, 0);

    @Test
    void disabledConnectorIsNeverDue() {
        assertThat(ConnectorScheduler.isDue(connector(0, 60, null), NOW, 60)).isFalse();
        assertThat(ConnectorScheduler.isDue(connector(null, 60, null), NOW, 60)).isFalse();
    }

    @Test
    void zeroIntervalMeansManualOnly() {
        assertThat(ConnectorScheduler.isDue(connector(1, 0, null), NOW, 60)).isFalse();
    }

    @Test
    void neverSyncedEnabledConnectorIsDue() {
        assertThat(ConnectorScheduler.isDue(connector(1, 60, null), NOW, 60)).isTrue();
    }

    @Test
    void connectorIsDueOnlyAfterIntervalElapsed() {
        assertThat(ConnectorScheduler.isDue(connector(1, 60, NOW.minusMinutes(59)), NOW, 60)).isFalse();
        assertThat(ConnectorScheduler.isDue(connector(1, 60, NOW.minusMinutes(60)), NOW, 60)).isTrue();
        assertThat(ConnectorScheduler.isDue(connector(1, 60, NOW.minusMinutes(61)), NOW, 60)).isTrue();
    }

    @Test
    void nullIntervalFallsBackToDefault() {
        assertThat(ConnectorScheduler.isDue(connector(1, null, NOW.minusMinutes(30)), NOW, 60)).isFalse();
        assertThat(ConnectorScheduler.isDue(connector(1, null, NOW.minusMinutes(30)), NOW, 20)).isTrue();
    }

    @Test
    void nullConnectorIsNotDue() {
        assertThat(ConnectorScheduler.isDue(null, NOW, 60)).isFalse();
    }

    private KbConnector connector(Integer enabled, Integer intervalMinutes, LocalDateTime lastSyncTime) {
        KbConnector connector = new KbConnector();
        connector.setEnabled(enabled);
        connector.setSyncIntervalMinutes(intervalMinutes);
        connector.setLastSyncTime(lastSyncTime);
        return connector;
    }
}
