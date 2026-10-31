/* Wire format: 960 mono float32 samples, little endian, 48 kHz (20 ms). */
class MicCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.packet = new Float32Array(960);
    this.used = 0;
  }
  process(inputs) {
    const channels = inputs[0];
    if (!channels || !channels.length) return true;
    for (let i = 0; i < channels[0].length; i++) {
      let sample = 0;
      for (const channel of channels) sample += channel[i];
      this.packet[this.used++] = sample / channels.length;
      if (this.used === 960) {
        // Explicit little-endian encoding keeps the future native app compatible.
        const bytes = new ArrayBuffer(960 * 4);
        const view = new DataView(bytes);
        for (let j = 0; j < 960; j++) view.setFloat32(j * 4, this.packet[j], true);
        this.port.postMessage(bytes, [bytes]);
        this.used = 0;
      }
    }
    return true;
  }
}
registerProcessor('mic-capture', MicCapture);
