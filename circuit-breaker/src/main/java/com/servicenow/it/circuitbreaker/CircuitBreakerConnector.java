/**
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 **/
        
/**
 * This file was automatically generated by the Mule Development Kit
 */
package com.servicenow.it.circuitbreaker;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.api.MuleContext;
import org.mule.api.annotations.Configurable;
import org.mule.api.annotations.Connector;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.param.Payload;
import org.mule.api.callback.SourceCallback;
import org.mule.api.config.MuleProperties;
import org.mule.api.store.ObjectStore;
import org.mule.api.store.ObjectStoreManager;
import org.mule.util.ClassUtils;

import javax.inject.Inject;
import java.util.Date;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cloud Connector
 *
 * @author MuleSoft, Inc.
 */
@Connector(name="circuitbreaker", schemaVersion="1.0-SNAPSHOT", friendlyName="Circuit Breaker")
public class CircuitBreakerConnector
{
    protected transient Log logger = LogFactory.getLog(getClass());

    /**
     * The amount of failures until the circuit breaker is tripped.
     */
    @Configurable
    private int tripThreshold;

    /**
     * How long to wait until the breaker is automatically reset.
     */
    @Configurable
    private long tripTimeout;

    /**
     * The name of this breaker.
     */
    @Configurable
    private String breakerName;

    private Date breakerTrippedOn;

    private Semaphore objectStoreMutex = new Semaphore(1);

    @Inject
    MuleContext muleContext;

    @Inject
    private ObjectStoreManager objectStoreManager;

    /**
     * Circuit breaker message processor
     *
     * {@sample.xml ../../../doc/CircuitBreaker-connector.xml.sample circuitbreaker:breaker}
     *
     * @param payload Incoming message to be processed or filtered
     * @param tripOnException trips if exception class name matches the provided regex pattern.
     * @return payload if circuit is closed
     */
    @Processor(intercepting = true)
    public Object breaker(SourceCallback afterChain, @Payload Object payload, String tripOnException) throws Exception {

        if (!isEnabled())
            return afterChain.process();

        boolean circuitOpen = false;
        Object processChainResponse = null;

        //Try to reset the circuit
        if (!isCircuitClosed() && breakerTrippedOn != null && System.currentTimeMillis() - breakerTrippedOn.getTime() > tripTimeout) {
            logger.debug("Resetting circuit breaker");

            breakerTrippedOn = null;
            resetFailureCount();
        }

        if (isCircuitClosed()) {
            logger.debug("Circuit closed, trying to process the next MP in chain");
            try {
//                processChainResponse = afterChain.process(RequestContext.getEvent());
                processChainResponse = afterChain.process();
            } catch (Exception e) {
                trip(tripOnException, e);
                if (!isCircuitClosed())
                    throw new CircuitOpenException();
                else
                    throw e;
            }
        } else {
            logger.debug("Circuit open!");
            throw new CircuitOpenException();
        }

        return processChainResponse;
    }

    @Processor
    public void reset() {
        breakerTrippedOn = null;
        resetFailureCount();
    }

    @Processor
    public void disable() throws Exception {
        changeStatus(false);
    }

    @Processor
    public void enable() throws Exception {
        changeStatus(true);
    }

    void changeStatus(boolean status) throws Exception {
        ObjectStore objectStore = objectStoreManager.getObjectStore(MuleProperties.OBJECT_STORE_DEFAULT_PERSISTENT_NAME);
        String key = String.format("%s.isEnabled", breakerName);
        if (objectStore.contains(key)) {
            objectStore.remove(key);
        }
        objectStore.store(key, new Boolean(status));
    }

    boolean isEnabled() throws Exception {
        boolean isEnabled = true;
        ObjectStore objectStore = objectStoreManager.getObjectStore(MuleProperties.OBJECT_STORE_DEFAULT_PERSISTENT_NAME);
        String key = String.format("%s.isEnabled", breakerName);
        if (objectStore.contains(key))
            isEnabled = ((Boolean)objectStore.retrieve(key)).booleanValue();
        return isEnabled;
    }

    boolean isCircuitClosed() {
        return (getFailureCount() < tripThreshold);
    }

    public void setMuleContext(MuleContext muleContext) {
        this.muleContext = muleContext;
    }
    public MuleContext getMuleContext() {
        return muleContext;
    }


    Integer getFailureCount() {
        try {
            objectStoreMutex.acquire();
        } catch (InterruptedException e) {
            logger.error("Could not acquire mutex", e);
        }

        ObjectStore objectStore = objectStoreManager.getObjectStore(MuleProperties.OBJECT_STORE_DEFAULT_PERSISTENT_NAME);

        String key = String.format("%s.failureCount", breakerName);

        Integer failureCount = 0;
        try {
            if (objectStore.contains(key)) {
                failureCount = (Integer) objectStore.retrieve(key);
            }
        } catch (Exception e) {
            logger.error("Could not retrieve key from object-store: " + key, e);
        }

        objectStoreMutex.release();

        return failureCount;

    }

    void incrementFailureCount() {
        try {
            objectStoreMutex.acquire();
        } catch (InterruptedException e) {
            logger.error("Could not acquire mutex", e);
        }

        ObjectStore objectStore = objectStoreManager.getObjectStore(MuleProperties.OBJECT_STORE_DEFAULT_PERSISTENT_NAME);


        String key = String.format("%s.failureCount", breakerName);

        Integer failureCount = 0;
        try {
            if (objectStore.contains(key)) {
                failureCount = (Integer) objectStore.retrieve(key);
                objectStore.remove(key);
            }
            objectStore.store(key, failureCount + 1);
        } catch (Exception e) {
            logger.error("Could not retrieve key from object-store: " + key, e);
        }

        objectStoreMutex.release();
    }

    void resetFailureCount() {
        try {
            objectStoreMutex.acquire();
        } catch (InterruptedException e) {
            logger.error("Could not acquire mutex", e);
        }

        ObjectStore objectStore = objectStoreManager.getObjectStore(MuleProperties.OBJECT_STORE_DEFAULT_PERSISTENT_NAME);


        String key = String.format("%s.failureCount", breakerName);

        Integer failureCount = 0;
        try {
            if (objectStore.contains(key)) {
                failureCount = (Integer) objectStore.retrieve(key);
                objectStore.remove(key);
            }
            objectStore.store(key, 0);
        } catch (Exception e) {
            logger.error("Could not retrieve key from object-store: " + key, e);
        }

        objectStoreMutex.release();
    }

    private void trip(String tripOnException, Exception exception) {
        logger.debug("Circuit breaker tripped!");
        exception.printStackTrace();
        if(null != tripOnException){
	        boolean match = false;
	
	        Throwable rootCause = ExceptionUtils.getRootCause(exception);
	        if (rootCause == null)
	            rootCause = exception;
	
	        String exceptionClassString = rootCause.getClass().getCanonicalName();
	        
	        String exceptions[] =  tripOnException.split(",");
	        for(int i =0 ; i<exceptions.length;i++){
		        try {
		            Class exceptionClass = ClassUtils.getClass(exceptions[i], false);
		            match = exceptionClass.isAssignableFrom(rootCause.getClass());
		        } catch (Exception e) {
		            logger.debug("String '" + exceptions[i] + "' is not a valid exception class name");
		            match = false;
		        }
		
		        if (!match) {
		            Pattern p = Pattern.compile(exceptions[i]);
		            Matcher m = p.matcher(exceptionClassString);
		            match = m.matches();
		        }
		
		        if (match) {
		            logger.debug("Exception pattern match");
		            incrementFailureCount();
		            if (getFailureCount() == tripThreshold) {
		                logger.debug("Failure count is " + getFailureCount());
		                breakerTrippedOn = new Date();
		                break;
		            }
		        } else {
		            logger.debug("Pattern " + exceptions[i] + " does not match " + exceptionClassString);
		        }
	        }
        }
        else{
        	logger.debug("Trip on Exception is null !!");
        }
    }

    public long getTripTimeout() {
        return tripTimeout;
    }

    public void setTripTimeout(long tripTimeout) {
        this.tripTimeout = tripTimeout;
    }

    public int getTripThreshold() {
        return tripThreshold;
    }

    public void setTripThreshold(int tripThreshold) {
        this.tripThreshold = tripThreshold;
    }

    public ObjectStoreManager getObjectStoreManager() {
        return objectStoreManager;
    }

    public void setObjectStoreManager(ObjectStoreManager objectStoreManager) {
        this.objectStoreManager = objectStoreManager;
    }

    public String getBreakerName() {
        return breakerName;
    }

    public void setBreakerName(String breakerName) {
        this.breakerName = breakerName;
    }

}
