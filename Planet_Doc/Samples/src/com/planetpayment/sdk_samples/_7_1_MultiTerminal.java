// Uses the SDK to connect to multiple terminals at the same time
// 
// For simplicity sake, it is assumed the terminal requester in each of the terminals is using SocketServer, Datalink
// If running this sample through the simulator, ensure there are no requester port collisions between simulator instances
//
// This sample will continously send Sale requests to each of the terminals configured until a key is pressed

package com.planetpayment.sdk_samples;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import integrate_clientsdk.CommunicationContext;
import integrate_clientsdk.Integra;
import integrate_clientsdk.Error.ErrorType;
import integrate_clientsdk.channel.ChannelSocketClient;
import integrate_clientsdk.channel.IChannel;
import integrate_clientsdk.datalink.Datalink;
import integrate_clientsdk.logger.Logger;
import integrate_clientsdk.request.IRequest;
import integrate_clientsdk.request.settlement.SaleRequest;
import integrate_clientsdk.response.IResponseHandler;
import integrate_clientsdk.response.IStatusUpdateHandler;
import integrate_clientsdk.response.Response;
import integrate_clientsdk.response.StatusUpdate;

public class _7_1_MultiTerminal {
    
    //Helper class to store terminal connection details
    static private class TerminalDetails {
        
        String szTerminalIp;
        int nTerminalPort;
        
        TerminalDetails(String szTerminalIp, int nTerminalPort) {
            this.szTerminalIp = szTerminalIp;
            this.nTerminalPort = nTerminalPort;
        }
    }
    
    //Helper class which will handle terminal requests and callbacks
    static private class Terminal implements Runnable, IStatusUpdateHandler, IResponseHandler {
        
        private Logger log;
        
        private TerminalDetails myTerminalDetails;
        
        private Integra myIntegra = null;
        private AtomicInteger nSequenceNumber = new AtomicInteger(0);
        private Object mySyncObject = new Object();
        
        /**
         * Creates a Terminal instance based on TerminalConnection details. Setups an Integra instance with itself as the handle for response, and status callbacks
         * @param myTerminalDetails
         */
        Terminal(TerminalDetails myTerminalDetails) {
            
            this.myTerminalDetails = myTerminalDetails;
            
            log = Logger.getLogger(getTerminalIdentification());
            log.info("Creating new terminal instance");
            
            IChannel myChannel = new ChannelSocketClient(myTerminalDetails.szTerminalIp, myTerminalDetails.nTerminalPort, 10);
            CommunicationContext myCommContext = new CommunicationContext(myChannel, new Datalink());
            myIntegra = new Integra(myCommContext, this, this);
        }

        @Override
        public void onResponse(Response myResponse) {
            
            Map<String, String> myOptions = myResponse.getOptions();
            
            if (myOptions.get(IRequest.TAG_SEQUENCENUMBER).equals(nSequenceNumber.toString())) {
                
                log.info("Terminal sent a response with result: " + myOptions.get(IRequest.TAG_RESULT));
                try {
                    synchronized (mySyncObject) {
                        mySyncObject.notify();
                    }
                    
                } catch (Exception e) {
                    log.error("An exception occurred while restarting terminal cycle: " + e.getMessage());
                }
                
            } else {
                
                log.warn("Terminal sent an unexpected response which does not match the expected sequence number");
                log.warn("Terminal sequence number: " + myOptions.get(IRequest.TAG_SEQUENCENUMBER));
                log.warn("Expected sequence number: " + nSequenceNumber.toString());
            }
        }

        @Override
        public void onStatusUpdate(StatusUpdate update) {

            log.info("Terminal sent status: " + update.getOptions().get(IRequest.TAG_STATUSCODE));
        }
        
        /**
         * Helper method which returns an identifiable string for this terminal
         * 
         * @return A string with this format : <terminal_ip>:<terminal_port>
         */
        private String getTerminalIdentification() {
            
            return this.myTerminalDetails.szTerminalIp + ":" + myTerminalDetails.nTerminalPort;
        }

        @Override
        public void run() {
            
            log.info("Requests have started");
            
            Map<String, String> mySaleOptions = new HashMap<String, String>();
            mySaleOptions.put(IRequest.TAG_AMOUNT, "10");
            mySaleOptions.put(IRequest.TAG_REQUESTERTRANSREFNUM, nSequenceNumber.toString());
            
            while (myIntegra != null) {
                try {
                    IRequest mySaleRequest = new SaleRequest(mySaleOptions);
                    
                    ErrorType nError = myIntegra.sendRequest(mySaleRequest, nSequenceNumber);
                    
                    if (nError == ErrorType.SUCCESS) {
                        try {
                            synchronized (mySyncObject) {
                                mySyncObject.wait();
                            }
                            
                        } catch (Exception e) {
                            log.error("An exception occurred while waiting for response: " + e.getMessage());
                            break;
                        }
                        
                    } else {
                        log.error("Integra was unable to send a request, aborting execution");
                        break;
                    }
                    
                } catch (Exception e) {
                    log.fatal(e.getMessage());
                }
            }
        }
    }
    
    //Terminal connection details
    static private TerminalDetails myTerminal_1 = new TerminalDetails("127.0.0.1", 1234);
    static private TerminalDetails myTerminal_2 = new TerminalDetails("127.0.0.1", 1235);
    
    //Helper list, which contains the connection details for all terminals running in this sample
    static private List<TerminalDetails> myTerminalDetails = Arrays.asList(myTerminal_1, myTerminal_2) ;
    
    //Holds our active terminal instances
    static private List<Thread> myTerminals = new ArrayList<Thread>();

    public static void main(String[] args) {
        
        //Initialize logger targetting console and most permissive logs available
        Logger.setLoggerSetting(Logger.LoggerSettings.LOG_LEVEL_AND_APPENDERS, "DEBUG, console");
        
        //Initialize all our terminal instances with their connection details
        for (TerminalDetails myTerminalDetail : myTerminalDetails) {
            myTerminals.add(new Thread(new Terminal(myTerminalDetail)));
        }
        
        //Run each of the terminal instances in it's own thread to demonstrate concurrency
        for (Thread myTerminalThread : myTerminals) {
            myTerminalThread.start();
        }
        
        Scanner keyboard = new Scanner(System.in);
        keyboard.nextLine();
        keyboard.close();
    }
}
