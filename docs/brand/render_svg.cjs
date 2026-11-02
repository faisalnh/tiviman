const sharp = require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES ? process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES + '/sharp' : 'sharp');
const [input, output, w, h] = process.argv.slice(2);
sharp(input, {density: 288}).resize({width: w ? Number(w) : undefined, height: h ? Number(h) : undefined}).png().toFile(output).catch(e => {console.error(e); process.exit(1);});
