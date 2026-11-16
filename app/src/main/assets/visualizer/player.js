"use strict";

const canvas = document.querySelector("canvas");
const pen = canvas.getContext("2d");
const button = document.querySelector("#listen");
const status = document.querySelector("#status");
const Audio = window.AudioContext || window.webkitAudioContext;
let controller;
let audio;
let animation;
let queueTime = 0;
let waitingForAudio = false;

function paint(analyser) {
    const bins = new Uint8Array(analyser.frequencyBinCount);
    const wave = new Uint8Array(analyser.fftSize);

    function frame(time) {
        const width = Math.min(1920, canvas.clientWidth);
        const height = Math.min(1080, canvas.clientHeight);
        if (canvas.width !== width || canvas.height !== height) {
            canvas.width = width;
            canvas.height = height;
        }
        analyser.getByteFrequencyData(bins);
        analyser.getByteTimeDomainData(wave);
        pen.fillStyle = "#050d13";
        pen.fillRect(0, 0, width, height);

        const spacing = width * .85 / 56;
        for (let bar = 0; bar < 56; bar++) {
            const low = Math.floor(1 + 300 * (bar / 56) ** 2);
            const high = Math.max(low + 1, Math.floor(1 + 300 * ((bar + 1) / 56) ** 2));
            let level = 0;
            for (let index = low; index < high; index++) level = Math.max(level, bins[index]);
            const size = Math.max(2, level / 255 * height * .38);
            pen.fillStyle = "hsl(" + (145 + bar * 2.1 + 18 * Math.sin(time / 1800)) + ",75%,60%)";
            const x = width * .075 + bar * spacing;
            pen.fillRect(x, height * .68 - size, spacing * .72, size);
            pen.globalAlpha = .18;
            pen.fillRect(x, height * .68 + 4, spacing * .72, size * .48);
            pen.globalAlpha = 1;
        }
        pen.beginPath();
        pen.strokeStyle = "#d3fff4";
        pen.lineWidth = 2.5;
        for (let index = 0; index < wave.length; index += 8) {
            const x = width * (.075 + .85 * index / wave.length);
            const y = height * (.76 + (wave[index] - 128) / 128 * .13);
            if (index === 0) pen.moveTo(x, y); else pen.lineTo(x, y);
        }
        pen.stroke();
        animation = requestAnimationFrame(frame);
    }
    animation = requestAnimationFrame(frame);
}

function playPacket(context, analyser, data, rate, frames) {
    if (waitingForAudio) {
        waitingForAudio = false;
        status.textContent = "";
    }
    if (queueTime > context.currentTime + .55) return;
    const buffer = context.createBuffer(2, frames, rate);
    for (let channel = 0; channel < 2; channel++) {
        const samples = buffer.getChannelData(channel);
        for (let frame = 0; frame < frames; frame++) {
            samples[frame] = data.getInt16(8 + frame * 4 + channel * 2, true) / 32768;
        }
    }
    const source = context.createBufferSource();
    source.buffer = buffer;
    source.connect(analyser);
    if (queueTime < context.currentTime) queueTime = context.currentTime + .12;
    source.start(queueTime);
    queueTime += buffer.duration;
}

async function listen(context, analyser, signal) {
    const response = await fetch("audio", { cache: "no-store", signal });
    if (!response.ok) throw new Error(await response.text());
    if (!response.body || !response.body.getReader) throw new Error("This TV browser cannot stream the visualizer.");
    const reader = response.body.getReader();
    let tail = new Uint8Array(0);
    try {
        while (true) {
            const result = await reader.read();
            if (result.done) break;
            const bytes = new Uint8Array(tail.length + result.value.length);
            bytes.set(tail);
            bytes.set(result.value, tail.length);
            let offset = 0;
            while (bytes.length - offset >= 8) {
                const view = new DataView(bytes.buffer, offset);
                const rate = view.getUint32(0, true);
                const frames = view.getUint32(4, true);
                if (frames > 2048 || (frames && (rate < 8000 || rate > 192000))) {
                    throw new Error("Unsupported audio format.");
                }
                const length = 8 + frames * 4;
                if (bytes.length - offset < length) break;
                if (frames) playPacket(context, analyser, view, rate, frames);
                offset += length;
            }
            tail = bytes.slice(offset);
        }
        status.textContent = "TV mode ended. Start it again in Reson to reconnect.";
    } finally {
        reader.releaseLock();
    }
}

button.addEventListener("click", async () => {
    if (controller) {
        controller.abort();
        return;
    }
    if (!Audio || !window.fetch || !window.AbortController) {
        status.textContent = "This TV browser does not support live audio.";
        return;
    }
    controller = new AbortController();
    try {
        audio = new Audio();
        await audio.resume();
        const analyser = audio.createAnalyser();
        analyser.fftSize = 1024;
        analyser.connect(audio.destination);
        queueTime = 0;
        paint(analyser);
        button.textContent = "Disconnect";
        waitingForAudio = true;
        status.textContent = "Play music in Reson to begin.";
        await listen(audio, analyser, controller.signal);
    } catch (error) {
        status.textContent = error.name === "AbortError" ? "" : error.message;
    } finally {
        cancelAnimationFrame(animation);
        if (audio && audio.state !== "closed") await audio.close();
        controller = null;
        audio = null;
        button.textContent = "Play on this TV";
    }
});

window.addEventListener("pagehide", () => {
    if (controller) controller.abort();
});
