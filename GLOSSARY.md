# Camylla

Camylla connects an Android phone to Windows so meeting applications can use the phone's camera.

## Language

**Phone**:
The Android device providing the camera picture to Camylla.
_Avoid_: Camera client, mobile webcam

**Desktop client**:
The Camylla application through which the user connects the phone and manages its webcam picture on Windows.
_Avoid_: Receiver app, PC app

**Camylla webcam**:
The camera device Windows applications can select to receive Camylla's picture.
_Avoid_: OBS source, desktop preview

**Consuming application**:
The Windows application using the Camylla webcam, such as a meeting application or OBS.
_Avoid_: Client

**Camera session**:
An interval the user explicitly starts on the phone during which Camylla may provide its camera picture to a trusted desktop. A connection interruption does not end the user's permission, but an explicit stop does.
_Avoid_: Connection, pairing

**Paired desktop**:
A desktop whose identity the user has explicitly trusted and can revoke. Pairing does not by itself authorize camera capture.
_Avoid_: Discovered desktop, active session
