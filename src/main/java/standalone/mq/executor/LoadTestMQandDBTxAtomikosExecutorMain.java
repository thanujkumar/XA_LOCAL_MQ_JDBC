package standalone.mq.executor;

import ch.qos.logback.classic.Level;
import com.atomikos.icatch.jta.UserTransactionImp;
import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.atomikos.jms.AtomikosConnectionFactoryBean;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.mq.jms.MQXAConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import oracle.ucp.jdbc.PoolXADataSource;
import oracle.ucp.jdbc.PoolXADataSourceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.jta.JtaTransactionManager;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StopWatch;

import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.XAConnectionFactory;
import javax.sql.DataSource;
import javax.transaction.SystemException;
import javax.xml.crypto.Data;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

//https://www.atomikos.com/Documentation/JtaProperties
public class LoadTestMQandDBTxAtomikosExecutorMain {

    static {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.WARN);
    }

    public MQConnectionFactory getMQQueueConnectionFactory() throws JMSException {
        MQConnectionFactory mqQCF = new MQXAConnectionFactory();//new MQQueueConnectionFactory();
        mqQCF.setHostName("localhost");
        mqQCF.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        mqQCF.setPort(1414);
        mqQCF.setQueueManager("DEVMQ");
        return mqQCF;
    }

    public DataSource getOracleDS () throws SQLException {
//        PoolXADataSource pooledXAUCPDS = PoolDataSourceFactory.getPoolXADataSource();//new PoolXADataSourceImpl(); //
//        pooledXAUCPDS.setURL("jdbc:oracle:thin:@//localhost:1521/orcl");
//        pooledXAUCPDS.setUser("APPDATA");
//        pooledXAUCPDS.setPassword("data");
//        pooledXAUCPDS.setConnectionFactoryClassName("oracle.jdbc.xa.client.OracleXADataSource");
//        pooledXAUCPDS.setInitialPoolSize(Runtime.getRuntime().availableProcessors());
//        pooledXAUCPDS.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);

        AtomikosDataSourceBean ds = new AtomikosDataSourceBean();
        ds.setXaDataSourceClassName("oracle.jdbc.xa.client.OracleXADataSource");
        ds.setUniqueResourceName("Oracle");
        ds.setMinPoolSize(Runtime.getRuntime().availableProcessors());
        ds.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
        Properties p = new Properties();
        p.setProperty("URL","jdbc:oracle:thin:@//localhost:1521/orcl");
        p.setProperty("user","APPDATA");
        p.setProperty("password", "app");
        ds.setXaProperties(p);
        return ds;
    }

    public CachingConnectionFactory cachingConnectionFactory(
            UserCredentialsConnectionFactoryAdapter userCredentialsConnectionFactoryAdapter) {
        CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory();
        // cachingConnectionFactory.setTargetConnectionFactory(userCredentialsConnectionFactoryAdapter);
        cachingConnectionFactory.setSessionCacheSize(16);
        cachingConnectionFactory.setReconnectOnException(true);
        return cachingConnectionFactory;
    }

    static TransactionTemplate txTmp;

    public static void main(String[] args) throws Exception {
        LoadTestMQandDBTxAtomikosExecutorMain m = new LoadTestMQandDBTxAtomikosExecutorMain();

        MQConnectionFactory qcf = m.getMQQueueConnectionFactory();

        //https://www.atomikos.com/pub/Documentation/Tomcat7Integration35/jta.properties
        AtomikosConnectionFactoryBean atomikosConF = new AtomikosConnectionFactoryBean();
        atomikosConF.setLocalTransactionMode(false);
        atomikosConF.setXaConnectionFactoryClassName("com.ibm.mq.jms.MQXAConnectionFactory");
        atomikosConF.setPoolSize(60);
        atomikosConF.setUniqueResourceName("TEST_XA_MQ");
        atomikosConF.setXaConnectionFactory((XAConnectionFactory) qcf);

        CachingConnectionFactory ccf = m.cachingConnectionFactory(null);
        ccf.setCacheConsumers(true);
        ccf.setCacheProducers(true);
        ccf.setTargetConnectionFactory(atomikosConF);


        JtaTransactionManager jtaTxMgr = new JtaTransactionManager();
        UserTransactionImp usrTx = new UserTransactionImp();
        usrTx.setTransactionTimeout(60);
        jtaTxMgr.setUserTransaction(usrTx);
        UserTransactionManager usrTxMgr = new UserTransactionManager();
        jtaTxMgr.setTransactionManager(usrTxMgr);

        txTmp = new TransactionTemplate(jtaTxMgr);

        JmsTemplate jmsT = new JmsTemplate(ccf);
        jmsT.setReceiveTimeout(60*1000);
        jmsT.setSessionTransacted(true);
        jmsT.setSessionAcknowledgeMode(Session.SESSION_TRANSACTED);

        DataSource ds = m.getOracleDS();
        JdbcTemplate jdbcT = new JdbcTemplate(ds);


        AtomicLong atomicLong = new AtomicLong();
        StopWatch timer = new StopWatch();
        timer.start();

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() );

        ForkJoinPool forkJoinPool = new ForkJoinPool();
        forkJoinPool.submit(() -> {
            LongStream.range(0,1000).parallel().forEach(index -> {
                System.out.println(Thread.currentThread());
                executorService.execute(()-> txTmp.execute(new TransactionCallbackWithoutResult() {

                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) {
                        System.out.println("----------->"+Thread.currentThread());
                        String text = "This is message id - "+index+"- by thread "+ Thread.currentThread();
                        jmsT.send("TESTQUEUE0", (session) -> {
                            TextMessage msg = session.createTextMessage();
                            msg.setText(text);
                            return msg;
                        });
                        //database
                        jdbcT.update("insert into A_TX_TEST (MSG) values('"+text+"')");
                        atomicLong.incrementAndGet();
//                        if (index % 2 == 0) {
//                            try {
//                                status.setRollbackOnly();
//                            } catch (Exception e) {
//                                e.printStackTrace();
//                            }
//                            //throw new RuntimeException("Rollback -> "+ index);
//                        }
                    }
                }));
            });
        }).get();

        executorService.shutdown();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                super.run();
                timer.stop();
                System.out.println("++++++++++++TOOK - "+ timer.shortSummary() +"-------for total of " +atomicLong.get());
            }
        });

    }
}
