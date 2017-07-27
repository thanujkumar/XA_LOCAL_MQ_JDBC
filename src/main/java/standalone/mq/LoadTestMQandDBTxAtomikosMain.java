package standalone.mq;

import ch.qos.logback.classic.Level;
import com.atomikos.icatch.jta.UserTransactionImp;
import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jms.AtomikosConnectionFactoryBean;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.mq.jms.MQXAConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.jta.JtaTransactionManager;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StopWatch;

import javax.jms.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

//https://www.atomikos.com/Documentation/JtaProperties
public class LoadTestMQandDBTxAtomikosMain {

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

    public CachingConnectionFactory cachingConnectionFactory(
            UserCredentialsConnectionFactoryAdapter userCredentialsConnectionFactoryAdapter) {
        CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory();
        // cachingConnectionFactory.setTargetConnectionFactory(userCredentialsConnectionFactoryAdapter);
        cachingConnectionFactory.setSessionCacheSize(5);
        cachingConnectionFactory.setReconnectOnException(true);
        return cachingConnectionFactory;
    }

    static TransactionTemplate txTmp;

    public static void main(String[] args) throws Exception {
        LoadTestMQandDBTxAtomikosMain m = new LoadTestMQandDBTxAtomikosMain();

        MQConnectionFactory qcf = m.getMQQueueConnectionFactory();

        //https://www.atomikos.com/pub/Documentation/Tomcat7Integration35/jta.properties
        AtomikosConnectionFactoryBean atomikosConF = new AtomikosConnectionFactoryBean();
        atomikosConF.setLocalTransactionMode(false);
        atomikosConF.setPoolSize(30);
        atomikosConF.setUniqueResourceName("TEST_XA_MQ");
        atomikosConF.setXaConnectionFactory((XAConnectionFactory) qcf);

        CachingConnectionFactory ccf = m.cachingConnectionFactory(null);
        ccf.setCacheConsumers(false);
        ccf.setCacheProducers(true);
        ccf.setTargetConnectionFactory(atomikosConF);


        JtaTransactionManager jtaTxMgr = new JtaTransactionManager();
        UserTransactionImp usrTx = new UserTransactionImp();
        usrTx.setTransactionTimeout(50000);
        jtaTxMgr.setUserTransaction(usrTx);
        UserTransactionManager usrTxMgr = new UserTransactionManager();
        jtaTxMgr.setTransactionManager(usrTxMgr);

        txTmp = new TransactionTemplate(jtaTxMgr);

        JmsTemplate jmsT = new JmsTemplate(ccf);
        jmsT.setReceiveTimeout(5000);
        jmsT.setSessionTransacted(true);
        jmsT.setSessionAcknowledgeMode(Session.SESSION_TRANSACTED);

        AtomicLong atomicLong = new AtomicLong();
        StopWatch timer = new StopWatch();
        timer.start();

        ForkJoinPool forkJoinPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        forkJoinPool.submit(() -> LongStream.range(0, 100000).parallel().forEach(index -> {

            txTmp.execute(new TransactionCallbackWithoutResult() {

                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    jmsT.send("TESTQUEUE1", (session) -> {
                        TextMessage msg = session.createTextMessage();
                        msg.setText("This is message id - "+index +" by thread "+ Thread.currentThread());
                        return msg;
                    });
                }
            });
            atomicLong.incrementAndGet();
        })).get();

        timer.stop();

        System.out.println("++++++++++++TOOK - "+ timer.shortSummary() +"-------for total of " +atomicLong.get());

        synchronized (jmsT) {
           // jmsT.wait();
        }
    }
}
