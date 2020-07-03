package com.example.gateopener;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

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
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;

public class MainActivity extends AppCompatActivity {

    // Topics
    public static final String GATE_COMMAND_TOPIC = "Hexdro/Gate/Command";
    public static final String GATE_STATUS_TOPIC = "Hexdro/Gate/Status";
    public static final String GATE_OPENING_STATUS = "opening";
    public static final String GATE_CLOSING_STATUS = "closing";

    // UI
    Button gateButon;
    private ProgressBar spinner;

    private Context mContext=MainActivity.this;
    private static final int REQUEST = 112;

    private String clientID;
    private MqttAndroidClient client;
    private IMqttToken token;

    private MqttConnectOptions mqttConnectOptions;

    Boolean state;
    Boolean firstStart=true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        state=false;

        // UI
        gateButon = findViewById(R.id.gate_btn);
        gateButon.setVisibility(View.INVISIBLE);
        spinner=(ProgressBar)findViewById(R.id.loading_spinner);
        spinner.setVisibility(View.VISIBLE);

//        FloatingActionButton fab = findViewById(R.id.fab);

        gateButon.setEnabled(false);
        gateButon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGate();
            }
        });

        // Check required permissions
        if (Build.VERSION.SDK_INT >= 23) {
            String[] PERMISSIONS = {Manifest.permission.WAKE_LOCK, Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,Manifest.permission.READ_PHONE_STATE};
            if (!hasPermissions(mContext, PERMISSIONS)) {
                ActivityCompat.requestPermissions((Activity) mContext, PERMISSIONS, REQUEST);
            }
        }

        // New Thread to handel MQTT
        Thread mqttThread = new Thread(){
            public void run(){
                mqttInit();
            }
        };

        // Start Thread
        mqttThread.run();

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

        // Disconnect mqtt
        try {
            client.disconnect();
        } catch (MqttException e) {
            Toast.makeText(MainActivity.this,e.getMessage(),Toast.LENGTH_SHORT).show();
        }

        // Disable and hide button
        gateButon.setEnabled(false);
        gateButon.setVisibility(View.INVISIBLE);
        firstStart=true;

    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        spinner.setVisibility(View.VISIBLE);
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

    // Init MQTT
    private void mqttInit(){

        // Setup client
        clientID = MqttClient.generateClientId();
        client = new MqttAndroidClient(this.getApplicationContext(), "tcp://test.mosquitto.org:1883",clientID);

        mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);


        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Toast.makeText(MainActivity.this,"Connected!",Toast.LENGTH_SHORT).show();
                subscribe();

                // Enable button
                gateButon.setEnabled(true);
            }

            @Override
            public void connectionLost(Throwable cause) {
                // Disable button
                gateButon.setEnabled(false);

                Toast.makeText(MainActivity.this,"Lost Connection...",Toast.LENGTH_SHORT).show();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {

//                Toast.makeText(MainActivity.this, "Message on : "+topic,Toast.LENGTH_SHORT).show();
//                Toast.makeText(MainActivity.this, message.toString(),Toast.LENGTH_SHORT).show();

                if (topic.equals(GATE_COMMAND_TOPIC) ){
//                    JSONObject reader = new JSONObject(message.toString());
//                    Toast.makeText(MainActivity.this, message.toString(),Toast.LENGTH_SHORT).show();
                }

                else if (topic.equals(GATE_STATUS_TOPIC)) {
                    JSONObject reader = new JSONObject(message.toString());
                    JSONObject gate  = reader.getJSONObject("gate");

                    String status = gate.getString("status");
                    Toast.makeText(MainActivity.this, status,Toast.LENGTH_SHORT).show();

                    if (status.equals(GATE_OPENING_STATUS)){
                        Drawable background = getResources().getDrawable(R.drawable.roundedbuttongreen);
                        gateButon.setBackground(background);
                        gateButon.setEnabled(true);
                        showGateButton();
                    } else if(status.equals(GATE_CLOSING_STATUS)){
                        Drawable background = getResources().getDrawable(R.drawable.roundedbuttonred);
                        gateButon.setBackground(background);
                        gateButon.setEnabled(true);
                        showGateButton();
                    }



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
     * Subscribe to status
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

    }

    private void toggleGate(){
        gateButon.setEnabled(false);
        if (client!=null) {
            String topic = "Hexdro/Gate/Command";
            String payload;
//                    if (state) payload="on";
//                    else payload = "off";
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

    private void showGateButton(){
        if (firstStart){
            gateButon.setVisibility(View.VISIBLE);
            Animation expandIn = AnimationUtils.loadAnimation(MainActivity.this,R.anim.fragment_open_enter);
            gateButon.startAnimation(expandIn);
            spinner.setVisibility(View.GONE);
            firstStart=!firstStart;
        }
    }

}


