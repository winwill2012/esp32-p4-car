FROM node:24-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts
COPY webrtc.mjs ./
COPY web/rtc-index.html web/rtc.js web/style.css ./web/
USER node
CMD ["node", "webrtc.mjs"]
