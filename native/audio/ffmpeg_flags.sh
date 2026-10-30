# 소리만 꺼내는 데 필요한 부분만 넣은 FFmpeg 구성 (작고 빠르게)
FFMPEG_FLAGS=(
  --disable-everything --disable-programs --disable-doc --disable-avdevice --disable-swscale
  --disable-postproc --disable-avfilter --disable-debug --disable-iconv --disable-xlib
  --disable-securetransport --disable-vulkan --disable-zlib --disable-bzlib --disable-lzma
  --enable-pic --enable-static --disable-shared --enable-network
  --enable-avformat --enable-avcodec --enable-swresample --enable-avutil
  --enable-protocol=file,http,tcp
  --enable-demuxer=avi,asf,matroska,mov,mpegts,mpegps,mpegvideo,flv,rm,ogg,wav,mp3,aac,ac3,eac3,dts,flac,truehd,amr,w64,xwma,dv,mxf,nut,vob,live_flv
  --enable-decoder=aac,aac_latm,mp1,mp1float,mp2,mp2float,mp3,mp3float,mp3adu,ac3,eac3,dca,truehd,mlp,wmav1,wmav2,wmapro,wmavoice,wmalossless,cook,ra_144,ra_288,sipr,atrac3,atrac3p,vorbis,opus,flac,alac,amrnb,amrwb,gsm,gsm_ms,qdm2,nellymoser,speex,pcm_s16le,pcm_s16be,pcm_u8,pcm_s8,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_f64le,pcm_alaw,pcm_mulaw,pcm_dvd,pcm_bluray,adpcm_ima_wav,adpcm_ms,adpcm_swf,adpcm_g726,dvaudio
  --enable-parser=aac,aac_latm,ac3,mpegaudio,dca,vorbis,opus,flac,mlp,amr,gsm,cook,sipr,dvaudio
)
