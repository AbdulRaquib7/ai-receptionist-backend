import { Device } from '@twilio/voice-sdk';

let device;

export async function startCall() {
  console.log("▶ startCall");

  const res = await fetch('/token');
  const { token } = await res.json();

  console.log("🔑 token received");

  device = new Device(token, {
    codecPreferences: ['opus', 'pcmu'],
    enableRingingState: true
  });

  device.on('ready', () => console.log('✅ Device ready'));
  device.on('connect', () => console.log('📞 Call connected'));
  device.on('disconnect', () => console.log('📴 Call disconnected'));
  device.on('error', e => console.error('❌ Twilio error', e));

  await device.connect();
}

export function endCall() {
  if (device) {
    device.disconnectAll();
    console.log("📴 Call ended");
  }
}

// expose to browser
window.startCall = startCall;
window.endCall = endCall;
