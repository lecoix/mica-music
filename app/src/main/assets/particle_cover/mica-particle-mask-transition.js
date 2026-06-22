const vertexShaderSource = `
  attribute vec2 aPosition;
  attribute vec2 aUv;
  varying vec2 vUv;

  void main() {
    vUv = aUv;
    gl_Position = vec4(aPosition, 0.0, 1.0);
  }
`;

const fragmentShaderSource = `
  precision mediump float;
  uniform sampler2D uImage;
  uniform sampler2D uRank;
  uniform float uProgress;
  uniform float uFeather;
  uniform float uAlpha;
  uniform float uGather;
  varying vec2 vUv;

  void main() {
    vec4 color = texture2D(uImage, vUv);
    float rank = texture2D(uRank, vUv).r;
    float scatterMask = smoothstep(uProgress - uFeather, uProgress + uFeather, rank);
    float gatherMask = 1.0 - smoothstep(uProgress - uFeather, uProgress + uFeather, rank);
    float mask = mix(scatterMask, gatherMask, uGather);
    gl_FragColor = vec4(color.rgb, color.a * mask * uAlpha);
  }
`;

(function(){
  const canvas = document.getElementById("maskCanvas");
  if (!canvas || !window.MicaParticleCover) return;

  const gl = canvas.getContext("webgl", {
    alpha: true,
    antialias: false,
    premultipliedAlpha: false,
    preserveDrawingBuffer: false
  });
  if (!gl) return;

  const durationMs = 900;
  const rankSize = 1024;
  // Matches the Three.js cover halo: side = min(canvas) / (1 + halo * 2).
  const coverHalo = 0.0025;
  const nativeSetCover = window.MicaParticleCover.setCover;
  const nativeResize = window.MicaParticleCover.resize;
  const nativeDebugState = window.MicaParticleCover.debugState;
  const program = createProgram(vertexShaderSource, fragmentShaderSource);
  const quadBuffer = createQuadBuffer();
  const rankTexture = createRankTexture(rankSize);
  let currentTexture = null;
  let previousTexture = null;
  let pendingTexture = null;
  let currentImage = null;
  let pendingImage = null;
  let currentId = null;
  let transitionStartedAt = 0;
  let transitionActive = false;
  let frameRequested = false;

  const locations = {
    aPosition: gl.getAttribLocation(program, "aPosition"),
    aUv: gl.getAttribLocation(program, "aUv"),
    uImage: gl.getUniformLocation(program, "uImage"),
    uRank: gl.getUniformLocation(program, "uRank"),
    uProgress: gl.getUniformLocation(program, "uProgress"),
    uFeather: gl.getUniformLocation(program, "uFeather"),
    uAlpha: gl.getUniformLocation(program, "uAlpha"),
    uGather: gl.getUniformLocation(program, "uGather")
  };

  function clamp(value, min, max){
    return Math.max(min, Math.min(max, value));
  }

  function outCubic(value){
    value = clamp(value, 0, 1);
    return 1 - Math.pow(1 - value, 3);
  }

  function inOutCubic(value){
    value = clamp(value, 0, 1);
    return value < 0.5 ? 4 * value * value * value : 1 - Math.pow(-2 * value + 2, 3) / 2;
  }

  function hash2(x, y, seed){
    let n = Math.imul(x + seed * 374761393, 668265263) ^ Math.imul(y + seed * 2246822519, 3266489917);
    n ^= n >>> 13;
    n = Math.imul(n, 1274126177);
    return ((n ^ (n >>> 16)) >>> 0) / 4294967295;
  }

  function buildRankField(size){
    const data = new Uint8Array(size * size);
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const u = (x + 0.5) / size;
        const v = (y + 0.5) / size;
        const edge = Math.min(u, 1 - u, v, 1 - v);
        const edgeBand = 1 - smoothstep(0.0, 0.22, edge);
        const coarse = hash2(Math.floor(u * 20), Math.floor(v * 20), 3);
        const mid = hash2(Math.floor(u * 56), Math.floor(v * 56), 7);
        const fine = hash2(Math.floor(u * 140), Math.floor(v * 140), 11);
        const streak = hash2(Math.floor((u + v * 0.32) * 44), Math.floor((v - u * 0.18) * 44), 17);
        const diagonal = 1 - Math.abs((u * 0.72 + v * 0.28) - 0.5) * 2;
        const rank = clamp(
          edgeBand * 0.44 +
            coarse * 0.25 +
            mid * 0.18 +
            fine * 0.07 +
            streak * 0.04 +
            diagonal * 0.02,
          0,
          1
        );
        data[y * size + x] = Math.round(rank * 255);
      }
    }
    return data;
  }

  function smoothstep(edge0, edge1, value){
    const t = clamp((value - edge0) / (edge1 - edge0), 0, 1);
    return t * t * (3 - 2 * t);
  }

  function loadImage(src){
    if (!src) return Promise.resolve(null);
    return new Promise(resolve => {
      const image = new Image();
      image.crossOrigin = "anonymous";
      image.onload = () => resolve(image);
      image.onerror = () => resolve(null);
      image.src = src;
    });
  }

  function resize(){
    const rect = canvas.getBoundingClientRect();
    const ratio = Math.min(window.devicePixelRatio || 1, 1.8);
    const width = Math.max(1, Math.round(rect.width * ratio));
    const height = Math.max(1, Math.round(rect.height * ratio));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    gl.viewport(0, 0, canvas.width, canvas.height);
  }

  function coverViewport(){
    const side = Math.min(canvas.width, canvas.height) / (1 + coverHalo * 2);
    return {
      x: Math.round((canvas.width - side) * 0.5),
      y: Math.round((canvas.height - side) * 0.5),
      size: Math.round(side)
    };
  }

  function createTextureFromImage(image){
    if (!image) return null;
    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
    return texture;
  }

  function createRankTexture(size){
    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.LUMINANCE, size, size, 0, gl.LUMINANCE, gl.UNSIGNED_BYTE, buildRankField(size));
    return texture;
  }

  function renderPass(texture, progress, alpha, gather){
    if (!texture || alpha <= 0) return;
    const viewport = coverViewport();
    gl.viewport(viewport.x, viewport.y, viewport.size, viewport.size);
    gl.useProgram(program);
    gl.bindBuffer(gl.ARRAY_BUFFER, quadBuffer);
    gl.enableVertexAttribArray(locations.aPosition);
    gl.vertexAttribPointer(locations.aPosition, 2, gl.FLOAT, false, 16, 0);
    gl.enableVertexAttribArray(locations.aUv);
    gl.vertexAttribPointer(locations.aUv, 2, gl.FLOAT, false, 16, 8);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.uniform1i(locations.uImage, 0);
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, rankTexture);
    gl.uniform1i(locations.uRank, 1);
    gl.uniform1f(locations.uProgress, progress);
    gl.uniform1f(locations.uFeather, 0.030);
    gl.uniform1f(locations.uAlpha, alpha);
    gl.uniform1f(locations.uGather, gather ? 1 : 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }

  function render(timestamp){
    resize();
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    if (!transitionActive) return;
    const elapsed = timestamp - transitionStartedAt;
    const p = clamp(elapsed / durationMs, 0, 1);
    if (p < 0.5) {
      const scatter = outCubic(p / 0.5);
      renderPass(previousTexture, scatter, 1 - scatter * 0.18, false);
    } else {
      const gather = inOutCubic((p - 0.5) / 0.5);
      renderPass(pendingTexture, gather, gather, true);
    }
    if (elapsed >= durationMs) {
      transitionActive = false;
      currentImage = pendingImage || currentImage;
      replaceTexture(currentTexture, pendingTexture);
      currentTexture = pendingTexture || currentTexture;
      pendingTexture = null;
      pendingImage = null;
      deleteTexture(previousTexture);
      previousTexture = null;
      gl.clear(gl.COLOR_BUFFER_BIT);
    }
  }

  function requestFrame(){
    if (frameRequested) return;
    frameRequested = true;
    window.requestAnimationFrame(frame);
  }

  function ensureLoop(){
    const scheduler = window.MicaParticleCoverFrameScheduler;
    if (scheduler) scheduler.wake();
    requestFrame();
  }

  function startTransition(nextImage, nextTexture){
    previousTexture = currentTexture;
    pendingImage = nextImage;
    pendingTexture = nextTexture;
    transitionStartedAt = performance.now();
    transitionActive = !!previousTexture && !!pendingTexture;
    if (!transitionActive) {
      deleteTexture(currentTexture);
      currentTexture = nextTexture || currentTexture;
      currentImage = nextImage || currentImage;
      pendingTexture = null;
      pendingImage = null;
      previousTexture = null;
      gl.clear(gl.COLOR_BUFFER_BIT);
    }
    ensureLoop();
  }

  window.MicaParticleCover.setCover = async function(payload){
    const result = await nativeSetCover(payload);
    const nextId = String(payload && payload.id || "cover");
    const nextImage = await loadImage(payload && payload.src);
    const nextTexture = createTextureFromImage(nextImage);
    if (!currentTexture || currentId === nextId || !(payload && payload.motionEnabled)) {
      deleteTexture(currentTexture);
      currentTexture = nextTexture || currentTexture;
      currentImage = nextImage || currentImage;
      currentId = nextId;
      transitionActive = false;
      gl.clear(gl.COLOR_BUFFER_BIT);
      return result;
    }
    currentId = nextId;
    startTransition(nextImage, nextTexture);
    return result;
  };

  window.MicaParticleCover.resize = function(){
    const result = nativeResize();
    resize();
    return result;
  };

  window.MicaParticleCover.debugState = function(){
    const state = nativeDebugState ? nativeDebugState() : {};
    state.rankTransitionActive = transitionActive;
    state.rankTextureSize = rankSize;
    return state;
  };

  function frame(timestamp){
    frameRequested = false;
    render(timestamp);
    if (transitionActive) requestFrame();
  }

  function createQuadBuffer(){
    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([
        -1, -1, 0, 1,
        1, -1, 1, 1,
        -1, 1, 0, 0,
        1, 1, 1, 0
      ]),
      gl.STATIC_DRAW
    );
    return buffer;
  }

  function createProgram(vertexSource, fragmentSource){
    const vertexShader = compileShader(gl.VERTEX_SHADER, vertexSource);
    const fragmentShader = compileShader(gl.FRAGMENT_SHADER, fragmentSource);
    const shaderProgram = gl.createProgram();
    gl.attachShader(shaderProgram, vertexShader);
    gl.attachShader(shaderProgram, fragmentShader);
    gl.linkProgram(shaderProgram);
    if (!gl.getProgramParameter(shaderProgram, gl.LINK_STATUS)) {
      throw new Error(gl.getProgramInfoLog(shaderProgram) || "Rank transition program link failed");
    }
    return shaderProgram;
  }

  function compileShader(type, source){
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error(gl.getShaderInfoLog(shader) || "Rank transition shader compile failed");
    }
    return shader;
  }

  function deleteTexture(texture){
    if (texture) gl.deleteTexture(texture);
  }

  function replaceTexture(oldTexture, newTexture){
    if (oldTexture && oldTexture !== newTexture) gl.deleteTexture(oldTexture);
  }

  resize();
  if ("ResizeObserver" in window) {
    new ResizeObserver(resize).observe(canvas);
  }
  window.addEventListener("resize", resize);
})();
