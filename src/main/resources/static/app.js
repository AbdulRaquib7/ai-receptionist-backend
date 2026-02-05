console.log("✅ app.js loaded");

let device;

async function initDevice() {
  console.log("▶ initDevice() called");
  // 1. Fetch token from backend
  const response = await fetch('/token');
  const data = await response.json();

  const token = data.token; // ← THIS is the token you saw in browser

  // 2. Create Twilio Device
  device = new Twilio.Device(token, {
    codecPreferences: ['opus', 'pcmu'],
    fakeLocalDTMF: true,
    enableRingingState: true
  });

  device.on('ready', () => console.log('✅ Twilio Device ready'));
  device.on('error', e => console.error('❌ Twilio error', e));
  device.on('connect', () => console.log('📞 Call connected'));
  device.on('disconnect', () => console.log('📴 Call disconnected'));
}

// Call button
document.getElementById('callBtn').onclick = async () => {
  if (!device) {
    await initDevice();
  }
  device.connect(); // ← THIS starts the WebRTC call
};

// Hangup button
document.getElementById('hangupBtn').onclick = () => {
  if (device) {
    device.disconnectAll();
  }
};
