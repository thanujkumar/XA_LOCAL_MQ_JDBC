package standalone.mq;

import javax.jms.Destination;
import javax.jms.JMSConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.Session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.transaction.PlatformTransactionManager;

import com.ibm.mq.jms.MQQueueConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;

public class StandaloneMain {

	@Bean
	public MQQueueConnectionFactory getMQQueueConnectionFactory() throws JMSException {
		MQQueueConnectionFactory mqQCF = new MQQueueConnectionFactory();
		mqQCF.setHostName("localhost");

		mqQCF.setTransportType(WMQConstants.WMQ_CM_CLIENT);
		// mqQCF.setCCSID(1208);
		// mqQCF.setChannel("");
		mqQCF.setPort(1414);
		mqQCF.setQueueManager("DEVMQ");
		return mqQCF;
	}

	@Bean
	UserCredentialsConnectionFactoryAdapter userCredentialsConnectionFactoryAdapter(
			MQQueueConnectionFactory mqQueueConnectionFactory) {
		UserCredentialsConnectionFactoryAdapter userCredentialsConnectionFactoryAdapter = new UserCredentialsConnectionFactoryAdapter();
		userCredentialsConnectionFactoryAdapter.setUsername("username");
		userCredentialsConnectionFactoryAdapter.setPassword("password");
		userCredentialsConnectionFactoryAdapter.setTargetConnectionFactory(mqQueueConnectionFactory);
		return userCredentialsConnectionFactoryAdapter;
	}

	@Bean
	@Primary
	public CachingConnectionFactory cachingConnectionFactory(
			UserCredentialsConnectionFactoryAdapter userCredentialsConnectionFactoryAdapter) {
		CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory();
		cachingConnectionFactory.setTargetConnectionFactory(userCredentialsConnectionFactoryAdapter);
		cachingConnectionFactory.setSessionCacheSize(500);
		cachingConnectionFactory.setReconnectOnException(true);
		return cachingConnectionFactory;
	}

	@Bean
	public PlatformTransactionManager jmsTransactionManager(CachingConnectionFactory cachingConnectionFactory) {
		JmsTransactionManager jmsTransactionManager = new JmsTransactionManager();
		jmsTransactionManager.setConnectionFactory(cachingConnectionFactory);
		return jmsTransactionManager;
	}

	@Bean
	public JmsOperations jmsOperations(CachingConnectionFactory cachingConnectionFactory) {
		JmsTemplate jmsTemplate = new JmsTemplate(cachingConnectionFactory);
		jmsTemplate.setReceiveTimeout(5000);
		return jmsTemplate;
	}
	
	public static void main(String[] args) throws Exception{
		//This is using only MQQueueConnectionFactory
		StandaloneMain m = new StandaloneMain();
		QueueConnectionFactory qcf = m.getMQQueueConnectionFactory();
		//QueueConnection qc = (QueueConnection) qcf.createConnection();
		
		//Session s = qc.createSession();
		
		JmsTemplate jmsT = new JmsTemplate(qcf);
		jmsT.setReceiveTimeout(5000);

		int times = 100;

		//jmsT.convertAndSend("TESTQUEUE1","Hello");
		for (int i = 0 ; i < times ; i++ ) {
			   final int x = i;
				jmsT.send("TESTQUEUE1", (session) -> {
					    Message msg = session.createMessage();
					    msg.setIntProperty("THANUJ", x);
						return  msg;
					}
				);
		}
		
		for (int i = 0; i < times; i++) {
			System.out.println(jmsT.receiveSelected("TESTQUEUE1","THANUJ="+i));
			Thread.sleep(1000);
		}
		
		synchronized (jmsT) {
			jmsT.wait();
		}		
	}
}
