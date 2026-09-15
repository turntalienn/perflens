package io.perflens.autoconfigure;

import io.perflens.core.context.PerfLensTraceContext;
import io.perflens.core.jfr.JfrSupport;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * JDBC proxies that feed {@link PerfLensTraceContext}.
 *
 * <ul>
 *   <li>{@code DataSource.getConnection()} is timed and pool gauges are read, giving
 *       checkout wait and connections held per trace.</li>
 *   <li>The connection tracks {@code setAutoCommit}/{@code commit}/{@code rollback}
 *       to time transactions and the SQL run inside them.</li>
 *   <li>Statements time every {@code execute*} call and keep the SQLState and error
 *       code of failures (for deadlock and lock-timeout detection). Prepared
 *       statements also hash their bound values (the values are not kept) so
 *       identical re-executions can be told apart from N+1 loops. Each traced
 *       statement is also a JFR {@code io.perflens.Sql} event.</li>
 * </ul>
 *
 * <p>Proxies are created for every connection, but recording is a no-op unless a
 * trace is open on the calling thread.</p>
 */
final class JdbcInstrumentation {

    private static final ClassLoader CLASS_LOADER = JdbcInstrumentation.class.getClassLoader();

    private JdbcInstrumentation() {
    }

    /** Advice on the DataSource proxy that wraps returned connections. */
    static final class DataSourceInterceptor implements MethodInterceptor {

        private final PoolProbe poolProbe;

        DataSourceInterceptor(PoolProbe poolProbe) {
            this.poolProbe = poolProbe;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            if (!"getConnection".equals(invocation.getMethod().getName())) {
                return invocation.proceed();
            }
            long start = System.nanoTime();
            Object result = invocation.proceed();
            long waitNanos = System.nanoTime() - start;
            if (!(result instanceof Connection connection) || isInstrumented(connection)) {
                return result;
            }
            boolean counted = false;
            if (PerfLensTraceContext.isCollecting()) {
                PoolProbe.PoolStats pool = poolProbe.read();
                counted = PerfLensTraceContext.recordConnectionAcquired(waitNanos, pool.active(), pool.max(),
                        pool.pending());
            }
            ConnectionHandler handler = new ConnectionHandler(connection, counted);
            handler.proxy = (Connection) Proxy.newProxyInstance(CLASS_LOADER, new Class<?>[] {Connection.class},
                    handler);
            return handler.proxy;
        }

        private static boolean isInstrumented(Connection connection) {
            return Proxy.isProxyClass(connection.getClass())
                    && Proxy.getInvocationHandler(connection) instanceof ConnectionHandler;
        }
    }

    /** Wraps one pooled connection for the duration of a checkout. */
    static final class ConnectionHandler implements InvocationHandler {

        private final Connection target;
        private final boolean counted;
        private Connection proxy;
        private boolean closed;
        private boolean autoCommit;
        private boolean inTransaction;
        private boolean explicitTransaction;
        private long transactionStart;
        private int transactionStatements;
        private long transactionSqlNanos;

        private ConnectionHandler(Connection target, boolean counted) {
            this.target = target;
            this.counted = counted;
            this.autoCommit = readAutoCommit(target);
            if (!autoCommit) {
                beginTransaction(false);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "prepareStatement":
                    return statementProxy(invokeTarget(method, target, args), (String) args[0], PreparedStatement.class);
                case "prepareCall":
                    return statementProxy(invokeTarget(method, target, args), (String) args[0], CallableStatement.class);
                case "createStatement":
                    return statementProxy(invokeTarget(method, target, args), null, Statement.class);
                case "setAutoCommit":
                    invokeTarget(method, target, args);
                    setAutoCommit((Boolean) args[0]);
                    return null;
                case "commit":
                    invokeTarget(method, target, args);
                    restartTransaction(true);
                    return null;
                case "rollback":
                    invokeTarget(method, target, args);
                    if (args == null) {
                        restartTransaction(false);
                    }
                    return null;
                case "close":
                    close(method);
                    return null;
                case "unwrap":
                    return ((Class<?>) args[0]).isInstance(target) ? target : target.unwrap((Class<?>) args[0]);
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(target) || target.isWrapperFor((Class<?>) args[0]);
                default:
                    return invokeTarget(method, target, args);
            }
        }

        void onStatement(long durationNanos) {
            if (inTransaction) {
                transactionStatements++;
                transactionSqlNanos += durationNanos;
            }
        }

        private Object statementProxy(Object statement, String sql, Class<?> type) {
            return Proxy.newProxyInstance(CLASS_LOADER, new Class<?>[] {type},
                    new StatementHandler((Statement) statement, sql, this));
        }

        private void setAutoCommit(boolean enabled) {
            if (!enabled && autoCommit) {
                beginTransaction(true);
            }
            else if (enabled && !autoCommit) {
                endTransaction(true);
            }
            autoCommit = enabled;
        }

        private void restartTransaction(boolean committed) {
            endTransaction(committed);
            if (!autoCommit) {
                beginTransaction(false);
            }
        }

        private void beginTransaction(boolean explicit) {
            inTransaction = true;
            explicitTransaction = explicit;
            transactionStart = System.nanoTime();
            transactionStatements = 0;
            transactionSqlNanos = 0;
        }

        /**
         * Records the open transaction. Implicit transactions (the one that starts
         * right after a commit on a manual-commit connection) are skipped when empty.
         */
        private void endTransaction(boolean committed) {
            if (!inTransaction) {
                return;
            }
            inTransaction = false;
            if (explicitTransaction || transactionStatements > 0) {
                PerfLensTraceContext.recordTransaction(System.nanoTime() - transactionStart, transactionStatements,
                        transactionSqlNanos, committed);
            }
        }

        private void close(Method method) throws Throwable {
            if (closed) {
                return;
            }
            closed = true;
            try {
                invokeTarget(method, target, null);
            }
            finally {
                endTransaction(false);
                if (counted) {
                    PerfLensTraceContext.recordConnectionReleased();
                }
            }
        }

        private static boolean readAutoCommit(Connection connection) {
            try {
                return connection.getAutoCommit();
            }
            catch (SQLException ex) {
                return true;
            }
        }
    }

    /** Wraps one statement; times executions and hashes bound parameters. */
    static final class StatementHandler implements InvocationHandler {

        private final Statement target;
        private final String sql;
        private final ConnectionHandler connection;
        private Map<Object, Integer> parameters;
        private List<Integer> batchParameters;
        private List<String> batchSql;

        private StatementHandler(Statement target, String sql, ConnectionHandler connection) {
            this.target = target;
            this.sql = sql;
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.startsWith("execute")) {
                return execute(method, args, name.endsWith("Batch"));
            }
            if (sql != null && args != null && args.length >= 2 && name.startsWith("set")) {
                bind(args);
            }
            else {
                switch (name) {
                    case "addBatch" -> addBatch(args);
                    case "clearParameters" -> parameters = null;
                    case "clearBatch" -> {
                        batchParameters = null;
                        batchSql = null;
                    }
                    case "getConnection" -> {
                        return connection.proxy;
                    }
                    default -> {
                    }
                }
            }
            return invokeTarget(method, target, args);
        }

        private Object execute(Method method, Object[] args, boolean batch) throws Throwable {
            String executed;
            int parameterHash = 0;
            if (sql != null) {
                executed = sql;
                parameterHash = batch ? Objects.hashCode(batchParameters) : parameterHash();
            }
            else if (args != null && args.length > 0 && args[0] instanceof String statementSql) {
                executed = statementSql;
            }
            else if (batch && batchSql != null) {
                executed = String.join(";\n", batchSql);
            }
            else {
                return invokeTarget(method, target, args);
            }
            if (batch) {
                batchParameters = null;
                batchSql = null;
            }
            Object jfrEvent = PerfLensTraceContext.isCollecting() ? JfrSupport.beginSql() : null;
            long start = System.nanoTime();
            try {
                Object result = invokeTarget(method, target, args);
                record(executed, parameterHash, System.nanoTime() - start, null, jfrEvent);
                return result;
            }
            catch (Throwable ex) {
                record(executed, parameterHash, System.nanoTime() - start, ex, jfrEvent);
                throw ex;
            }
        }

        private void record(String executed, int parameterHash, long durationNanos, Throwable failure,
                Object jfrEvent) {
            PerfLensTraceContext.recordSql(executed, parameterHash, durationNanos, failure);
            connection.onStatement(durationNanos);
            if (jfrEvent != null) {
                JfrSupport.commitSql(jfrEvent, PerfLensTraceContext.currentTraceId().orElse(null), executed,
                        failure == null);
            }
        }

        private void bind(Object[] args) {
            if (!PerfLensTraceContext.isCollecting()) {
                return;
            }
            if (parameters == null) {
                parameters = new HashMap<>();
            }
            parameters.put(args[0], Arrays.deepHashCode(Arrays.copyOfRange(args, 1, args.length)));
        }

        private void addBatch(Object[] args) {
            if (args == null) {
                if (batchParameters == null) {
                    batchParameters = new ArrayList<>();
                }
                batchParameters.add(parameterHash());
            }
            else if (args[0] instanceof String statementSql) {
                if (batchSql == null) {
                    batchSql = new ArrayList<>();
                }
                batchSql.add(statementSql);
            }
        }

        private int parameterHash() {
            return parameters == null ? 0 : parameters.hashCode();
        }
    }

    private static Object invokeTarget(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }
}
