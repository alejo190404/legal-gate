package com.legalgate.intake.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcIntakeRepositoryTests {

    @Test
    void recordSuccessfulLoginExecutesLoginAuditFunctionWithoutUsingUpdateCount() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        JdbcIntakeRepository repository =
                new JdbcIntakeRepository(jdbcTemplate, transactionTemplate, new ObjectMapper());

        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<TransactionStatus>>getArgument(0)
                    .accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        doAnswer(invocation -> {
            PreparedStatement statement = mock(PreparedStatement.class);
            PreparedStatementCallback<?> callback = invocation.getArgument(1);
            callback.doInPreparedStatement(statement);
            verify(statement).setString(1, "owner@firma.test");
            verify(statement).execute();
            verify(statement, never()).executeUpdate();
            return null;
        }).when(jdbcTemplate).execute(eq("select app_record_user_login(?)"), any(PreparedStatementCallback.class));

        repository.recordSuccessfulLogin("owner@firma.test");

        verify(jdbcTemplate).execute(eq("select app_record_user_login(?)"), any(PreparedStatementCallback.class));
        verify(jdbcTemplate, never()).update("select app_record_user_login(?)", "owner@firma.test");
    }
}
