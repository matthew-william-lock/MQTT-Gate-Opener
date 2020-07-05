/*****
 
 All the resources for this project:
 insert URL HERE
 
*****/

#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

// Change the credentials below, so your ESP8266 connects to your router
const char* ssid = "WR7010-2.4G-1DA";
const char* password = "xzad67467";

// IP configuration
IPAddress ip(192, 168, 137, 25); // where xx is the desired IP Address
IPAddress gateway(192, 168, 1, 1); // set gateway to match your network
IPAddress subnet(255, 255, 255, 0); // set subnet mask to match your network

// Change the variable to your Raspberry Pi IP address, so it connects to your MQTT broker
const char* mqtt_server = "test.mosquitto.org";

// Initializes the espClient. You should change the espClient name if you have multiple ESPs running in your home automation system
WiFiClient espClient;
PubSubClient client(espClient);

// Pins
#define wifiLed 12
#define gatePin 15
#define gateStatusPin 13
#define relayPin 14

// States
const String OPENING_STATUS ="opening";
const String CLOSING_STATUS ="closing";
const String OPEN_STATUS ="open";
const String CLOSED_STATUS ="closed";
const String OPENINGCLOSING_STATUS = "openclose";
const String UNDETERMINED_STATUS ="und";
const String CLOSING_STOPPED_STATUS = "closing_stopped";
const String OPENING_STOPPED_STATUS = "opening_stopped"; 

// Timers auxiliar variables and previous gate status
long lastMeasure = 0;
long lastInterrupt = 0;
long threshold = 1200;
boolean lastGateStatus = false;
boolean check = true;

// Gate 
String gateCommand = "Hexdro/Gate/Command";
String gateStatus= "Hexdro/Gate/Status";
bool gate_is_open = false;
String gateStatusString = UNDETERMINED_STATUS;

// Ack
String ACKREQTOPIC = "Hexdro/Gate/AckReq";
String ACKREPTOPIC = "Hexdro/Gate/AckReply";

// JSON Objects
StaticJsonDocument<100> docAck;
StaticJsonDocument<150> AckBack;
StaticJsonDocument<38> doc;

JsonObject root = doc.to<JsonObject>();
JsonObject gate = root.createNestedObject("gate");

JsonObject obj = AckBack.to<JsonObject>();
JsonObject ack_respone = obj.createNestedObject("ack_response");

// State keeping
bool started=false;
int i =0;

// Populate JSON Data
void setupJSON() {
  delay(10);
  gate["status"] = gate_is_open;
  ack_respone["client"]="";
}

// Don't change the function below. This functions connects your ESP8266 to your router
void setup_wifi() {
  delay(10);
  // We start by connecting to a WiFi network
  Serial.println();
  Serial.print("Connecting to ");
  Serial.println(ssid);
  
  WiFi.mode(WIFI_STA);
  WiFi.config(ip, gateway, subnet);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  digitalWrite(wifiLed, HIGH);
  Serial.println("");
  Serial.print("WiFi connected - ESP IP address: ");
  Serial.println(WiFi.localIP());
}

// This functions is executed when some device publishes a message to a topic that your ESP8266 is subscribed to
// Change the function below to add logic to your program, so when a device publishes a message to a topic that 
// your ESP8266 is subscribed you can actually do something
void callback(String topic, byte* message, unsigned int length) {
  Serial.print("Message arrived on topic: ");
  Serial.print(topic);
  Serial.print(". Message: ");
  String messageTemp;
  
  for (int i = 0; i < length; i++) {
    Serial.print((char)message[i]);
    messageTemp += (char)message[i];
  }
  Serial.println();

  // Toggles relay to trigger gate button
  if(topic==gateCommand){

      if(messageTemp == "toggle") {
        toggleRelay();
      }

  }

  // Ack request from connected device
  else if (topic==ACKREQTOPIC){
    
    // Deserialize the JSON object
    DeserializationError error = deserializeJson(docAck, messageTemp);

    // Test if parsing succeeds.
    if (error) {
      Serial.print(F("deserializeJson() failed: "));
      Serial.println(error.c_str());
      return;
    }

    const char* ackClient = docAck["ack_request"]["client"];

    // Send Ack Back
    ack_respone["client"] = ackClient;
    String statusData="";
    serializeJsonPretty(obj, statusData);;
    client.publish(ACKREPTOPIC.c_str(), statusData.c_str());

    // Print client.
    Serial.print("ACK sent to " +String(ackClient));
    ack_respone["client"].clear();
    AckBack.clear();

  }
  
  Serial.println();
}

// This functions reconnects your ESP8266 to your MQTT broker
// Change the function below if you want to subscribe to more topics with your ESP8266 
void reconnect() {
  // Loop until we're reconnected
  while (!client.connected()) {
    Serial.print("Attempting MQTT connection...");
    // Attempt to connect
    if (client.connect("ESP8226_Gate")) {
      Serial.println("connected");  
      // Subscribe or resubscribe to a topic
      // You can subscribe to more topics (to control more LEDs in this example)
      client.subscribe(gateCommand.c_str());
      client.subscribe(ACKREQTOPIC.c_str());
      client.publish("Hexdro", "hello world ESP8226 ! ");
      Serial.println("Subscribed to "+gateCommand);
      Serial.println("Subscribed to "+ACKREQTOPIC);

      /* Send persisting status */
      if (!started) {
        started=!started;
        lastInterrupt=millis();
      }

    } else {
      Serial.print("failed, rc=");
      Serial.print(client.state());
      Serial.println(" try again in 5 seconds");
      // Wait 5 seconds before retrying
      delay(5000);
    }
  }
}

// Publish Status of gate to MQTT
void publishStatus(){

  // Update JSON object
  gate["status"] = gateStatusString;

  String statusData="";
  // sprintf(statusData,"%d \n",60);
  serializeJsonPretty(root, statusData);
  client.publish(gateStatus.c_str(), statusData.c_str(),true);
  doc.clear();
}

// Function to trigger relay conneted to ESP8266
void toggleRelay(){
  digitalWrite(relayPin,HIGH);
  delay(50);
  digitalWrite(relayPin,LOW);
  Serial.println("Toggled relay");
}

// This function is triggered when the gate status pin voltage is changed
// Determines the current state of the gate once it has changed
ICACHE_RAM_ATTR void checkGateStatusInterupt(){

  // Update time last interrupt occured
  lastInterrupt = millis();

  if (millis()-lastMeasure > 200 ){

    // Stop automatic status check from occuring
    check = false;

    // Measure time
    long now = millis();
    long timeElsapsed = now - lastMeasure;
    threshold = 1200;

    // Record input
    bool gateStatusReading = digitalRead(gateStatusPin);

    // Determine state
    String previousGateStatusString = gateStatusString;
    if (timeElsapsed<500){
      if (gateStatusString == UNDETERMINED_STATUS) gateStatusString = OPENINGCLOSING_STATUS;
      else if(gateStatusString == OPENINGCLOSING_STATUS) gateStatusString = OPENINGCLOSING_STATUS;
      else if (gateStatusString == CLOSING_STATUS) gateStatusString = CLOSING_STOPPED_STATUS;
      else if (gateStatusString == OPENING_STATUS) gateStatusString = OPENING_STOPPED_STATUS;

    } else if(timeElsapsed<2000 ){      
      if (gateStatusString == UNDETERMINED_STATUS) gateStatusString = OPENINGCLOSING_STATUS;
      else if(gateStatusString == OPENINGCLOSING_STATUS) gateStatusString = OPENINGCLOSING_STATUS;
      else if (gateStatusString == OPEN_STATUS) gateStatusString = CLOSING_STATUS; 
      else if (gateStatusString == CLOSED_STATUS ) gateStatusString = OPENING_STATUS;
      else if (gateStatusString == CLOSING_STOPPED_STATUS) gateStatusString = OPENING_STATUS;
      else if (gateStatusString == OPENING_STOPPED_STATUS) gateStatusString = CLOSING_STATUS;

    } else{
      if (gateStatusReading) gateStatusString = OPEN_STATUS;
      else gateStatusString = CLOSED_STATUS;
    }

    // Display status LED
    digitalWrite(gatePin, gateStatusReading);

    // Time and state tracking
    lastGateStatus = gateStatusReading;
    lastMeasure = now;

    // Serial output update
    Serial.println(String(i)+" Interrupt Checking status "+gateStatusString);
    i++;

    // Let automatic status check occur
    check = true;

    // Publish current state of gate if state has changed
    if (gateStatusString!=previousGateStatusString) publishStatus();

  }

}

// This function can be called whenever the status of the gate needs to be determined
// Not to be used when gate status is actively changing (i.e. when opening/closing is occuring)
ICACHE_RAM_ATTR void checkGateStatus(){

  // Update time last interrupt occured
  lastInterrupt = millis();

  // Stop automatic status check from occurin
  check = false;

  // Measure time
  long now = millis();
  long timeElsapsed = now - lastMeasure;

  // Record input
  bool gateStatusReading = digitalRead(gateStatusPin);

  // Determine state
  if (gateStatusReading) gateStatusString = OPEN_STATUS;
  else gateStatusString = CLOSED_STATUS;  

  // Display status LED
  digitalWrite(gatePin, gateStatusReading);

  // Time and state tracking
  lastGateStatus = gateStatusReading;
  lastMeasure = now;

  // Time and state tracking
  Serial.println(String(i)+" Checking status "+gateStatusString);
  i++;

  // Let automatic status check occur
  check = true;

  // Publish current state of gate
  publishStatus();

}

// The setup function sets your ESP GPIOs to Outputs, starts the serial communication at a baud rate of 115200
// Sets your mqtt broker and sets the callback function
// The callback function is what receives messages and actually controls the LEDs
void setup() {

  // Setup pins
  pinMode(wifiLed, OUTPUT);
  pinMode(gatePin, OUTPUT);
  pinMode(relayPin, OUTPUT);
  pinMode(gateStatusPin, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(gateStatusPin), checkGateStatusInterupt, CHANGE);

  // Set pins
  digitalWrite(gatePin, LOW);
  digitalWrite(relayPin,LOW);
  
  // Start serial Connection
  Serial.begin(115200);

  // Setup device
  setupJSON();
  setup_wifi();
  client.setServer(mqtt_server, 1883);
  client.setCallback(callback);

}

// For this project, you don't need to change anything in the loop function. Basically it ensures that you ESP is connected to your broker
void loop() {

  if (!client.connected()) {
    reconnect();
  }
  if(!client.loop())
    client.connect("ESP8226_Gate");
  
  // If an interrupt has not occured 
  long timeSinceLastInterrupt = millis() - lastInterrupt;
  if (timeSinceLastInterrupt > threshold && check) {
    Serial.print("Automatic Timer ");
    checkGateStatus();
    threshold = 60000;
  }
  

}
