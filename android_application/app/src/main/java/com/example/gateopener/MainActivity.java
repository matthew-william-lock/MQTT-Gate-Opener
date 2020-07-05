package com.example.gateopener;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    /* Topics */
    public static final String GATE_COMMAND_TOPIC = "Hexdro/Gate/Command";
    public static final String GATE_STATUS_TOPIC = "Hexdro/Gate/Status";
    public static final String GATE_CLOSED_STATUS = "closed";
    public static final String GATE_CLOSING_STATUS = "closing";
    public static final String GATE_OPEN_STATUS = "open";
    public static final String GATE_OPENING_STATUS = "opening";
    public static final String GATE_OPENING_CLOSING_STATUS  = "openclose";
    public static final String GATE_UNDETERMINED_STATUS ="und";
    public static final String GATE_CLOSING_STOPPED_STATUS   = "closing_stopped";
    public static final String GATE_OPENING_STOPPED_STATUS  = "opening_stopped";
    public static final String GATE_ACK_REQ = "Hexdro/Gate/AckReq";
    public static final String GATE_ACK_REP = "Hexdro/Gate/AckReply";

    /* Timer */
    public static final int TIMER_PERIOD = 3000;

    /* UI */
    Button gateButon;
    private ProgressBar spinner;

    /* Permissions */
    private Context mContext=MainActivity.this;
    private static final int REQUEST = 112;

    /* Mqtt */
    private String clientID;
    private MqttAndroidClient client;
    private IMqttToken token;
    private MqttConnectOptions mqttConnectOptions;

    /* State Keeping */
    Boolean firstStart=true;
    int ackSent=0;
    int ackReceived=0;
    boolean running=true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        /* Ui */
        gateButon = findViewById(R.id.gate_btn);
        gateButon.setVisibility(View.INVISIBLE);
        spinner=(ProgressBar)findViewById(R.id.loading_spinner);

        // Ensure gate button can't be pressed until connected to device
        spinner.setVisibility(View.VISIBLE);
        gateButon.setEnabled(false);

        // On click listener
        gateButon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGate();
            }
        });

        /* Check required permissions */
        if (Build.VERSION.SDK_INT >= 23) {
            String[] PERMISSIONS = {Manifest.permission.WAKE_LOCK, Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,Manifest.permission.READ_PHONE_STATE};
            if (!hasPermissions(mContext, PERMISSIONS)) {
                ActivityCompat.requestPermissions((Activity) mContext, PERMISSIONS, REQUEST);
            }
        }

        /* New Thread to handel MQTT */
        Thread mqttThread = new Thread(new Runnable() {
            @Override
            public void run() {
                mqttInit();
            }
        });

        // Start Thread
        mqttThread.run();

        final Handler mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message message) {
                ackRequest();
            }
        };

        /* Timer to handle regular ack requests */
        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Message message = mHandler.obtainMessage(1, "ack_request");
                message.sendToTarget();
            }
        }, 0, TIMER_PERIOD);//put here time 1000 milliseconds=1 second

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        /* Disconnect mqtt */
        try {
            client.disconnect();
        } catch (MqttException e) {
            Toast.makeText(MainActivity.this,e.getMessage(),Toast.LENGTH_SHORT).show();
        }

        /* Disable and hide button */
        gateButon.setEnabled(false);
        gateButon.setVisibility(View.INVISIBLE);
        firstStart=true;

        /* Change state */
        running=false;

        /* Reset Acks */
        ackSent=0;
        ackReceived=0;

    }

    @Override
    protected void onPostResume() {
        super.onPostResume();

        /* Change state */
        running=true;

        /* Show loading spinner while connecting */
        spinner.setVisibility(View.VISIBLE);

        /* Reconnect to mqtt broker */
        try{
            token = client.connect(mqttConnectOptions);
            token.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
//                    Toast.makeText(MainActivity.this,"Connected! "+client.isConnected(),Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Toast.makeText(MainActivity.this,"Failed to connect.",Toast.LENGTH_SHORT).show();
                }

            });
        }
        catch (MqttException e){
            Toast.makeText(MainActivity.this,e.getMessage(),Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /* =============================================================================================================================================================
     * Function responsible for checking permissions
     * ============================================================================================================================================================= */

    private static boolean hasPermissions(Context context, String... permissions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    /* =============================================================================================================================================================
     * Function responsible for requesting permissions
     * ============================================================================================================================================================= */

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    //do here
                } else {
                    Toast.makeText(mContext, "The app was not allowed to write in your storage", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /* =============================================================================================================================================================
     * Initialise mqtt connection
     * ============================================================================================================================================================= */
    private void mqttInit(){

        /* Setup Client */
        clientID = MqttClient.generateClientId();
        client = new MqttAndroidClient(this.getApplicationContext(), "tcp://test.mosquitto.org:1883",clientID);

        /* Connect to Client */
        mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Toast.makeText(MainActivity.this,"Connected!",Toast.LENGTH_SHORT).show();
                subscribe();

                /* Enable button */
                gateButon.setEnabled(true);
            }

            @Override
            public void connectionLost(Throwable cause) {
                /* Disable button */
                gateButon.setEnabled(false);
                Toast.makeText(MainActivity.this,"Lost Connection...",Toast.LENGTH_SHORT).show();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {

                // New Thread to handle re-enabling of button
                Thread showButtonThread = new Thread(new Runnable(){
                    public void run(){
                        while (ackReceived<1);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showGateButton();
                            }
                        });
                    }
                });

                /* Show messages and topic received - for debugging */
                /* Toast.makeText(MainActivity.this, "Message on : "+topic,Toast.LENGTH_SHORT).show();
                Toast.makeText(MainActivity.this, message.toString(),Toast.LENGTH_SHORT).show();  */

                if (topic.equals(GATE_COMMAND_TOPIC) ){
                    /* Do stuff*/
                }

                /* Receive message related to gate status*/
                else if (topic.equals(GATE_STATUS_TOPIC)) {

                    /* Decode received JSON file*/
                    JSONObject reader = new JSONObject(message.toString());
                    JSONObject gate  = reader.getJSONObject("gate");

                    /* Extract status*/
                    String status = gate.getString("status");
                    Toast.makeText(MainActivity.this, "initial "+status,Toast.LENGTH_SHORT).show();

                    if (status.equals(GATE_OPEN_STATUS) || status.equals(GATE_CLOSED_STATUS) || status.equals(GATE_OPENING_STOPPED_STATUS) || status.equals(GATE_CLOSING_STOPPED_STATUS)) {
                        Drawable background = getResources().getDrawable(R.drawable.roundedbuttongreen);
                        gateButon.setBackground(background);
                        gateButon.setEnabled(true);
                        showButtonThread.start();
                        if (status.equals(GATE_OPEN_STATUS) || status.equals(GATE_OPENING_STOPPED_STATUS)) gateButon.setText(getString(R.string.gate_button_close_string));
                        else if (status.equals(GATE_CLOSED_STATUS) || status.equals(GATE_CLOSING_STOPPED_STATUS)) gateButon.setText(getString(R.string.gate_button_open_string));
                    } else if (status.equals(GATE_OPENING_STATUS) || status.equals(GATE_CLOSING_STATUS)){
                        Drawable background = getResources().getDrawable(R.drawable.roundedbuttonred);
                        gateButon.setBackground(background);
                        gateButon.setEnabled(true);
                        showButtonThread.start();
                        if (status.equals(GATE_OPEN_STATUS)) gateButon.setText(getString(R.string.gate_button_close_string));
                        else if (status.equals(GATE_CLOSED_STATUS)) gateButon.setText(getString(R.string.gate_button_open_string));
                    } else if (status.equals(GATE_UNDETERMINED_STATUS) || status.equals(GATE_OPENING_CLOSING_STATUS)){
                        Drawable background = getResources().getDrawable(R.drawable.roundedbuttonamber);
                        gateButon.setBackground(background);
                        gateButon.setEnabled(true);
                        showButtonThread.start();
                        gateButon.setText(getString(R.string.gate_button_toggle_string));
                    }
                }

                /* Receive ack reply*/
                else if(topic.equals(GATE_ACK_REP)){
                    JSONObject reader = new JSONObject(message.toString());
                    JSONObject ack_response  = reader.getJSONObject("ack_response");

                    // Toast.makeText(MainActivity.this, client,Toast.LENGTH_SHORT).show();
                    String client = ack_response.getString("client");
                    if (client.equals(clientID)) ackReceived++;

                }

            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        /* =============================================================================================================================================================
        * Connect to Client
        * ============================================================================================================================================================= */

        try{
            token = client.connect(mqttConnectOptions);
            token.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
//                    Toast.makeText(MainActivity.this,"Connected! "+client.isConnected(),Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Toast.makeText(MainActivity.this,"Failed to connect.",Toast.LENGTH_SHORT).show();
                }

            });
        }
        catch (MqttException e){
            Toast.makeText(MainActivity.this,e.getMessage(),Toast.LENGTH_SHORT).show();
        }

    }

    /* =============================================================================================================================================================
     * Subscribe to topics
     * ============================================================================================================================================================= */
    private void subscribe(){
        int qos = 1;

        try {
            IMqttToken subToken = client.subscribe(GATE_STATUS_TOPIC, qos);
            subToken.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken,Throwable exception) {
                    Toast.makeText(MainActivity.this,"Could not connect to "+GATE_STATUS_TOPIC,Toast.LENGTH_SHORT).show();
                }
            });
        } catch (MqttException e) {
            Toast.makeText(MainActivity.this,e.getMessage(),Toast.LENGTH_SHORT).show();
        }

        try {
            IMqttToken subToken = client.subscribe(GATE_ACK_REP, qos);
            subToken.setActionCallback(new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken,Throwable exception) {
                    Toast.makeText(MainActivity.this,"Could not connect to "+GATE_STATUS_TOPIC,Toast.LENGTH_SHORT).show();
                }
            });
        } catch (MqttException e) {
            Toast.makeText(MainActivity.this,e.getMessage(),Toast.LENGTH_SHORT).show();
        }

    }

    /* =============================================================================================================================================================
     * Send mqtt command to toggle gate open/close
     * ============================================================================================================================================================= */

    private void toggleGate(){

        /* Prevent user from double clicking*/
        gateButon.setEnabled(false);

        /* Send toggle message*/
        if (client!=null) {
            String topic = "Hexdro/Gate/Command";
            String payload;
            payload="toggle";
            byte[] encodedPayload = new byte[0];
            try {
                encodedPayload = payload.getBytes("UTF-8");
                MqttMessage message = new MqttMessage(encodedPayload);
                client.publish(topic, message);
                Toast.makeText(MainActivity.this,payload,Toast.LENGTH_SHORT).show();
            } catch (UnsupportedEncodingException | MqttException e) {
                Toast.makeText(MainActivity.this,e.getMessage(),Toast.LENGTH_SHORT).show();
            }
        }
    }

    /* =============================================================================================================================================================
     * Show button
     * ============================================================================================================================================================= */

    private void showGateButton(){
        if (firstStart){
            gateButon.setVisibility(View.VISIBLE);
            Animation expandIn = AnimationUtils.loadAnimation(MainActivity.this,R.anim.fragment_open_enter);
            gateButon.startAnimation(expandIn);
            spinner.setVisibility(View.GONE);
            firstStart=!firstStart;
        }
    }

    /* =============================================================================================================================================================
     * Request ack to make sure mqtt device still connected
     * ============================================================================================================================================================= */

    private void ackRequest() {

        /* Send ack if connected to client and not waiting for previous ack */
        if (client.isConnected() && ackSent==ackReceived && running){
            sendAck();
            ackSent++;

            // Could update this to stop user from pressing button until update received
            gateButon.setEnabled(true);

        } else if(client.isConnected() && ackSent!=ackReceived && running){
            // Send ack until it is received again
            Toast.makeText(MainActivity.this,"Gate is not connected.",Toast.LENGTH_SHORT).show();

            /* Keep sending acks until previous ack is acknowledged */
            gateButon.setEnabled(false);
            sendAck();
        }
    }

    /* =============================================================================================================================================================
     * Send ack request to device through mqtt broker
     * ============================================================================================================================================================= */

    private void sendAck(){

        /* Create JSON Object to Send */
        JSONObject json = new JSONObject();
        JSONObject ack = new JSONObject();

        /* Send ack request */
        try {
            ack.put("client",clientID);
            json.put("ack_request",ack);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        String payload = json.toString();
        byte[] encodedPayload = new byte[0];
        try {
            encodedPayload = payload.getBytes("UTF-8");
            MqttMessage message = new MqttMessage(encodedPayload);
            client.publish(GATE_ACK_REQ, message);
        } catch (UnsupportedEncodingException | MqttException e) {
            e.printStackTrace();
        }
    }

}


