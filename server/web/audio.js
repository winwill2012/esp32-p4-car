const wireRate = 16000;
const samplesPerPacket = 320;

class IntercomProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.capture = false;
    this.packet = new Int16Array(samplesPerPacket);
    this.packetPosition = 0;
    this.sum = 0;
    this.count = 0;
    this.capturePhase = 0;
    this.playPhase = 0;
    this.ring = new Int16Array(4096);
    this.read = 0;
    this.write = 0;
    this.available = 0;
    this.playing = false;
    this.port.onmessage = ({ data }) => {
      if (typeof data.capture === 'boolean') {
        this.capture = data.capture;
        this.packetPosition = 0;
        this.capturePhase = 0;
        this.sum = 0;
        this.count = 0;
      }
      if (data.pcm) {
        for (const sample of new Int16Array(data.pcm)) {
          if (this.available >= samplesPerPacket * 6) {
            this.read = (this.read + samplesPerPacket) % this.ring.length;
            this.available -= samplesPerPacket;
          }
          this.ring[this.write] = sample;
          this.write = (this.write + 1) % this.ring.length;
          this.available++;
        }
      }
    };
  }

  process(inputs, outputs) {
    const input = inputs[0][0];
    const output = outputs[0][0];
    if (this.capture && input) {
      for (const sample of input) {
        this.sum += sample;
        this.count++;
        this.capturePhase += wireRate;
        if (this.capturePhase >= sampleRate) {
          this.capturePhase -= sampleRate;
          this.packet[this.packetPosition++] = Math.max(-32768, Math.min(32767, this.sum * 32768 / this.count));
          this.sum = 0;
          this.count = 0;
          if (this.packetPosition === samplesPerPacket) {
            this.port.postMessage(this.packet.buffer, [this.packet.buffer]);
            this.packet = new Int16Array(samplesPerPacket);
            this.packetPosition = 0;
          }
        }
      }
    }
    if (!this.playing && this.available >= samplesPerPacket * 2) this.playing = true;
    for (let i = 0; this.playing && i < output.length; i++) {
      if (this.available < 2) {
        this.playing = false;
        this.playPhase = 0;
        break;
      }
      const current = this.ring[this.read];
      const next = this.ring[(this.read + 1) % this.ring.length];
      output[i] = (current + (next - current) * this.playPhase / sampleRate) / 32768;
      this.playPhase += wireRate;
      if (this.playPhase >= sampleRate) {
        this.playPhase -= sampleRate;
        this.read = (this.read + 1) % this.ring.length;
        this.available--;
      }
    }
    return true;
  }
}

registerProcessor('p4-intercom', IntercomProcessor);
