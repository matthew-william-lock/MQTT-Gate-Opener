/*****
 
 All the resources for this project:
 https://randomnerdtutorials.com/
 
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

// States
const String OPENING ="opening";
const String CLOSING ="closing";
const String OPEN ="open";
const String CLOSED ="closed";
const String OPENINGCLOSING = "openclose";
const String UNDETERMINED ="und";

// Lamp - LED - GPIO 4 = D2 on ESP-12E NodeMCU board
const int lamp = 13;

// Timers auxiliar variables and previous gate status
long lastMeasure = 0;
long threshold = 1200;
boolean lastGateStatus = false;

// Gate 
String gateCommand = "Hexdro/Gate/Command";
String gateStatus= "Hexdro/Gate/Status";
bool gate_is_open = false;
bool gateStatusString = UNDETERMINED;

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

// Populate JSON Data
void setupJSON() {
  delay(10);
  gate["status"] = gate_is_open;
  ack_respone["client"]="";
}

// Send persisting state on start up
void initGateStatus(){

  // Get Data
  if(gate["status"]){
    gate["status"] = "opening";
    Serial.println("Gate is opening");
  }
  else {
    gate["status"] = "closing";
    Serial.println("Gate is closing");
  }

  // Write data to mqtt server
  String statusData="";
  serializeJsonPretty(root, statusData);
  client.publish(gateStatus.c_str(), statusData.c_str(),true);
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
//  digitalWrite(lamp, LOW);
  
  for (int i = 0; i < length; i++) {
    Serial.print((char)message[i]);
    messageTemp += (char)message[i];
  }
  Serial.println();

  // Feel free to add more if statements to control more GPIOs with MQTT

  // If a message is received on the topic room/lamp, you check if the message is either on or off. Turns the lamp GPIO according to the message
  if(topic==gateCommand){
      // Serial.print("Changing Room lamp to ");

      if(messageTemp == "toggle") gate_is_open=!gate_is_open;

      if (gate_is_open) {
        Serial.print("Gate is opening");
        digitalWrite(gatePin, HIGH);
        gate["status"] = "opening";
      }

      else {
        Serial.print("Gate is closing");
        digitalWrite(gatePin, LOW);
        gate["status"] = "closing";
      }

      String statusData="";
      // sprintf(statusData,"%d \n",60);
      serializeJsonPretty(root, statusData);
      client.publish(gateStatus.c_str(), statusData.c_str(),true);
  }

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
    /*
     YOU MIGHT NEED TO CHANGE THIS LINE, IF YOU'RE HAVING PROBLEMS WITH MQTT MULTIPLE CONNECTIONS
     To change the ESP device ID, you will have to give a new name to the ESP8266.
     Here's how it looks:
       if (client.connect("ESP8266Client")) {
     You can do it like this:
       if (client.connect("ESP1_Office")) {
     Then, for the other ESP:
       if (client.connect("ESP2_Garage")) {
      That should solve your MQTT multiple connections problem
    */
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
        initGateStatus();
        started=!started;
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

// This function is triggered when the gate status pin voltage is changed
// Determines the current state of the gate once it has changed
ICACHE_RAM_ATTR void checkGateStatus(){

  // gate_is_open=!gate_is_open;
  // digitalWrite(gatePin, gate_is_open);

  // Measure time
  long now = millis();
  long timeElsapsed = now - lastMeasure;
  threshold = 1200;

  // Record input
  bool gateStatusReading = digitalRead(gateStatusPin);

  // Determine state
  if(timeElsapsed<2000 && lastGateStatus != gateStatusReading){
    
    if (gateStatusString == UNDETERMINED) gateStatusString = OPENINGCLOSING;
    else if(gateStatusString == OPENINGCLOSING) gateStatusString = OPENINGCLOSING;
    else if (gateStatusString == OPEN) gateStatusString = CLOSING;
    else if (gateStatusString == CLOSED ) gateStatusString = OPENING;

  } else{
    if (gateStatusReading) gateStatusString = CLOSED;
    else gateStatusReading = OPEN;
  }

  digitalWrite(gatePin, gateStatusReading);

  lastGateStatus = gateStatusReading;
  lastMeasure = now;

}

// The setup function sets your ESP GPIOs to Outputs, starts the serial communication at a baud rate of 115200
// Sets your mqtt broker and sets the callback function
// The callback function is what receives messages and actually controls the LEDs
void setup() {

  // Setup pins
  pinMode(wifiLed, OUTPUT);
  pinMode(gatePin, OUTPUT);
  pinMode(gateStatusPin, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(gateStatusPin), checkGateStatus, CHANGE);

  // Set pins
  digitalWrite(gatePin, LOW);
  
  Serial.begin(115200);

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
  long timeSinceLastInterrupt = millis() - lastMeasure;
  if (timeSinceLastInterrupt > threshold) {
    checkGateStatus();
    threshold = 60000;
  }
  

}
