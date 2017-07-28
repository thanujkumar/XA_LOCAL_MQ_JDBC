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

import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.XAConnectionFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;

//https://www.atomikos.com/Documentation/JtaProperties
public class LoadTestMQandDBTxAtomikosMultiInstanceMain {

    static {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.DEBUG);
    }

    public MQConnectionFactory getMQQueueConnectionFactory() throws JMSException {
        MQConnectionFactory mqQCF = new MQXAConnectionFactory();//new MQQueueConnectionFactory();
        mqQCF.setConnectionNameList("10.246.89.117(1414),10.246.89.118(1414)");
        //mqQCF.setClientReconnectOptions(WMQConstants.WMQ_CLIENT_RECONNECT_Q_MGR);
        mqQCF.setClientReconnectOptions(WMQConstants.WMQ_CLIENT_RECONNECT);
        mqQCF.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        mqQCF.setClientReconnectTimeout(600);
        mqQCF.setChannel("CBS.CL");
        mqQCF.setQueueManager("QT0GNMQ");
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
        LoadTestMQandDBTxAtomikosMultiInstanceMain m = new LoadTestMQandDBTxAtomikosMultiInstanceMain();

        MQConnectionFactory qcf = m.getMQQueueConnectionFactory();

        //https://www.atomikos.com/pub/Documentation/Tomcat7Integration35/jta.properties
        AtomikosConnectionFactoryBean atomikosConF = new AtomikosConnectionFactoryBean();
        atomikosConF.setXaConnectionFactoryClassName("com.ibm.mq.jms.MQXAConnectionFactory");
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
        usrTx.setTransactionTimeout(60);// in seconds
        jtaTxMgr.setUserTransaction(usrTx);
        UserTransactionManager usrTxMgr = new UserTransactionManager();
        jtaTxMgr.setTransactionManager(usrTxMgr);

        txTmp = new TransactionTemplate(jtaTxMgr);

        JmsTemplate jmsT = new JmsTemplate(ccf);
        jmsT.setReceiveTimeout(60*1000);//in millis
        jmsT.setSessionTransacted(true);
        jmsT.setSessionAcknowledgeMode(Session.SESSION_TRANSACTED);

        //TODO works sequential
//        LongStream.range(0, 10).sequential().forEach(index -> {
//            txTmp.execute(new TransactionCallbackWithoutResult() {
//                @Override
//                protected void doInTransactionWithoutResult(TransactionStatus status) {
//                    jmsT.send("PERFORMANCE.TEST.QUEUE.LOCAL3"/*"TESTQUEUE1"*/, (session) -> {
//                        TextMessage msg = session.createTextMessage();
//                        msg.setText("This is message id  by thread -"+index+"-"+ Thread.currentThread());
//                        System.out.println("+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++"+System.currentTimeMillis());
//                        return msg;
//                    });
//                }
//            });
//        });


        LongStream.range(0,10).sequential().forEach(index -> {
            System.out.println(Thread.currentThread()+"********************************"+System.currentTimeMillis());
                txTmp.execute(new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(TransactionStatus status) {
                        jmsT.send("PERFORMANCE.TEST.QUEUE.LOCAL3"/*"TESTQUEUE1"*/, (session) -> {
                            TextMessage msg = session.createTextMessage();
                            msg.setText("This is message id  by thread "+ Thread.currentThread());
                            System.out.println(Thread.currentThread()+"+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++"+System.currentTimeMillis());
                            return msg;
                        });
                    }
                });

            });

     }
}



