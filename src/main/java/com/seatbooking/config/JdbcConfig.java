package com.seatbooking.config;

import java.sql.SQLException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * Spring's default translation turns PostgreSQL's lock_timeout into an uncategorized exception and
 * statement_timeout into a resource failure, both of which would surface as 5xx. Decorator pattern:
 * those two SQL states are mapped to the transient exceptions the global handler answers with 409
 * and 429; every other error keeps Spring's default translation.
 */
@Configuration
public class JdbcConfig {

    @Bean
    static BeanPostProcessor postgresTimeoutTranslation() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof JdbcTemplate jdbcTemplate) {
                    jdbcTemplate.setExceptionTranslator(
                            new PostgresTimeoutTranslator(jdbcTemplate.getExceptionTranslator()));
                }
                return bean;
            }
        };
    }

    static final class PostgresTimeoutTranslator extends AbstractFallbackSQLExceptionTranslator {

        private static final String LOCK_NOT_AVAILABLE = "55P03";
        private static final String QUERY_CANCELED = "57014";

        PostgresTimeoutTranslator(SQLExceptionTranslator fallback) {
            setFallbackTranslator(fallback);
        }

        @Override
        protected DataAccessException doTranslate(String task, String sql, SQLException ex) {
            String message = buildMessage(task, sql, ex);
            return switch (String.valueOf(ex.getSQLState())) {
                case LOCK_NOT_AVAILABLE -> new CannotAcquireLockException(message, ex);
                case QUERY_CANCELED -> new QueryTimeoutException(message, ex);
                default -> null;
            };
        }
    }
}
