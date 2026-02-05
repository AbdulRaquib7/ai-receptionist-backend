let device;

async function init() {
  const res = await fetch('/token');
  const data = await res.json();

  device = new Twilio.Device(data.token, {
    codecPreferences: ['opus', 'pcmu'],
    fakeLocalDTMF: true,
    enableRingingState: true
  });

  device.on('ready', () => console.log('Twilio Device ready'));
  device.on('error', e => console.error('Twilio error', e));
}

document.getElementById('call').onclick = async () => {
  if (!device) await init();
  device.connect();
};

document.getElementById('hangup').onclick = () => {
  if (device) device.disconnectAll();
};
