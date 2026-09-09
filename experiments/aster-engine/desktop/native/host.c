/* Aster's deliberately narrow QuickJS host. No quickjs-libc, module loader,
 * filesystem, shell, sockets, workers, native-module loading or print binding. */
#include "quickjs.h"
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef _WIN32
#include <windows.h>
#include <Xinput.h>
#include <io.h>
#include <fcntl.h>
#else
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#ifdef __linux__
#include <linux/joystick.h>
#include <sys/ioctl.h>
#endif
#endif

#define MAX_MESSAGE (2 * 1024 * 1024)
static double deadline;
static int controller_enabled;
static double now_ms(void) {
#ifdef _WIN32
    return (double)GetTickCount64();
#else
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
#endif
}
static int interrupted(JSRuntime *rt, void *opaque) { (void)rt; (void)opaque; return now_ms() > deadline; }
static void put_number(JSContext *ctx, JSValue obj, const char *key, double n) { JS_SetPropertyStr(ctx, obj, key, JS_NewFloat64(ctx, n)); }
static JSValue gamepads(JSContext *ctx, JSValueConst self, int argc, JSValueConst *argv) {
    (void)self; (void)argc; (void)argv;
    JSValue pads = JS_NewArray(ctx);
    if (!controller_enabled) return pads;
    for (int index=0; index<4; index++) {
        double axes[16]={0}, buttons[32]={0}; int na=0, nb=0; const char *mapping="";
#ifdef _WIN32
        typedef DWORD (WINAPI *StateFn)(DWORD, XINPUT_STATE *);
        static HMODULE lib; static StateFn get_state;
        if (!lib) { lib=LoadLibraryExW(L"xinput1_4.dll", NULL, LOAD_LIBRARY_SEARCH_SYSTEM32); if(lib) get_state=(StateFn)(void *)GetProcAddress(lib,"XInputGetState"); }
        XINPUT_STATE s; if(!get_state || get_state(index,&s)!=ERROR_SUCCESS) { JS_SetPropertyUint32(ctx,pads,index,JS_NULL); continue; }
        WORD keys[17]={XINPUT_GAMEPAD_A,XINPUT_GAMEPAD_B,XINPUT_GAMEPAD_X,XINPUT_GAMEPAD_Y,XINPUT_GAMEPAD_LEFT_SHOULDER,XINPUT_GAMEPAD_RIGHT_SHOULDER,0,0,XINPUT_GAMEPAD_BACK,XINPUT_GAMEPAD_START,XINPUT_GAMEPAD_LEFT_THUMB,XINPUT_GAMEPAD_RIGHT_THUMB,XINPUT_GAMEPAD_DPAD_UP,XINPUT_GAMEPAD_DPAD_DOWN,XINPUT_GAMEPAD_DPAD_LEFT,XINPUT_GAMEPAD_DPAD_RIGHT,0};
        for(int b=0;b<17;b++) buttons[b]=(s.Gamepad.wButtons & keys[b]) ? 1.0 : 0.0;
        buttons[6]=s.Gamepad.bLeftTrigger/255.0; buttons[7]=s.Gamepad.bRightTrigger/255.0;
        SHORT raw[4]={s.Gamepad.sThumbLX,s.Gamepad.sThumbLY,s.Gamepad.sThumbRX,s.Gamepad.sThumbRY};
        for(int a=0;a<4;a++) axes[a]=raw[a]/(raw[a]<0 ? 32768.0 : 32767.0)*(a%2 ? -1 : 1);
        na=4; nb=17; mapping="standard";
#elif defined(__linux__)
        static int fds[4]={-1,-1,-1,-1}; static double saved_axes[4][16], saved_buttons[4][32];
        if(fds[index]<0) { char path[32]; snprintf(path,sizeof(path),"/dev/input/js%d",index); fds[index]=open(path,O_RDONLY|O_NONBLOCK|O_CLOEXEC); if(fds[index]>=0) { memset(saved_axes[index],0,sizeof(saved_axes[index])); memset(saved_buttons[index],0,sizeof(saved_buttons[index])); } }
        if(fds[index]<0) { JS_SetPropertyUint32(ctx,pads,index,JS_NULL); continue; }
        struct js_event e; ssize_t n=0; int events=0;
        while(events++<256 && (n=read(fds[index],&e,sizeof(e)))==(ssize_t)sizeof(e)) {
            int type=e.type & ~JS_EVENT_INIT;
            if(type==JS_EVENT_AXIS && e.number<16) saved_axes[index][e.number]=e.value/(e.value<0 ? 32768.0 : 32767.0);
            if(type==JS_EVENT_BUTTON && e.number<32) saved_buttons[index][e.number]=e.value ? 1.0 : 0.0;
        }
        if(n==0 || (n<0 && errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR)) { close(fds[index]); fds[index]=-1; JS_SetPropertyUint32(ctx,pads,index,JS_NULL); continue; }
        memcpy(axes,saved_axes[index],sizeof(axes)); memcpy(buttons,saved_buttons[index],sizeof(buttons));
        unsigned char axis_count=0,button_count=0;ioctl(fds[index],JSIOCGAXES,&axis_count);ioctl(fds[index],JSIOCGBUTTONS,&button_count);
        na=axis_count<16?axis_count:16;nb=button_count<32?button_count:32;
#else
        JS_SetPropertyUint32(ctx,pads,index,JS_NULL); continue;
#endif
        JSValue p=JS_NewObject(ctx), aa=JS_NewArray(ctx), bb=JS_NewArray(ctx);
        JS_SetPropertyStr(ctx,p,"id",JS_NewString(ctx, mapping[0] ? "Aster XInput controller" : "Aster Linux joystick (raw mapping)"));
        JS_SetPropertyStr(ctx,p,"mapping",JS_NewString(ctx,mapping));
        JS_SetPropertyStr(ctx,p,"connected",JS_NewBool(ctx,1)); put_number(ctx,p,"index",index); put_number(ctx,p,"timestamp",now_ms());
        for(int a=0;a<na;a++) JS_SetPropertyUint32(ctx,aa,a,JS_NewFloat64(ctx,axes[a]));
        for(int b=0;b<nb;b++) { JSValue value=JS_NewObject(ctx); put_number(ctx,value,"value",buttons[b]); JS_SetPropertyStr(ctx,value,"pressed",JS_NewBool(ctx,buttons[b]>0.5)); JS_SetPropertyStr(ctx,value,"touched",JS_NewBool(ctx,buttons[b]>0)); JS_SetPropertyUint32(ctx,bb,b,value); }
        JS_SetPropertyStr(ctx,p,"axes",aa); JS_SetPropertyStr(ctx,p,"buttons",bb); JS_SetPropertyUint32(ctx,pads,index,p);
    }
    return pads;
}
static void send_value(JSContext *ctx, JSValue value) {
    JSValue json=JS_JSONStringify(ctx,value,JS_UNDEFINED,JS_UNDEFINED); size_t length=0;
    const char *allocated=JS_ToCStringLen(ctx,&length,json), *s=allocated;
    if(!s || length>MAX_MESSAGE) { s="{\"error\":\"Script result exceeded the response limit\"}"; length=strlen(s); }
    unsigned char header[4]={(unsigned char)(length>>24),(unsigned char)(length>>16),(unsigned char)(length>>8),(unsigned char)length};
    fwrite(header,1,4,stdout); fwrite(s,1,length,stdout); fflush(stdout);
    if(allocated) JS_FreeCString(ctx,allocated);
    JS_FreeValue(ctx,json);
}
/* Synchronous, framed RPC to the parent. The parent binds the real page origin;
 * this function has no filesystem, networking or arbitrary native entry point. */
static JSValue site_request(JSContext *ctx, JSValueConst self, int argc, JSValueConst *argv) {
    (void)self;
    if(argc!=1 || !JS_IsString(argv[0]))return JS_ThrowTypeError(ctx,"Expected one site-data request");
    size_t length;const char *request=JS_ToCStringLen(ctx,&length,argv[0]);
    if(!request)return JS_EXCEPTION;
    if(length>262144){JS_FreeCString(ctx,request);return JS_ThrowRangeError(ctx,"Site-data request limit");}
    uint32_t frame=(uint32_t)length|0x80000000u;
    unsigned char header[4]={(unsigned char)(frame>>24),(unsigned char)(frame>>16),(unsigned char)(frame>>8),(unsigned char)frame};
    int ok=fwrite(header,1,4,stdout)==4 && fwrite(request,1,length,stdout)==length && fflush(stdout)==0;
    JS_FreeCString(ctx,request);
    if(!ok || fread(header,1,4,stdin)!=4)return JS_ThrowInternalError(ctx,"Site-data pipe closed");
    uint32_t size=((uint32_t)header[0]<<24)|((uint32_t)header[1]<<16)|((uint32_t)header[2]<<8)|header[3];
    if(size>262144)return JS_ThrowRangeError(ctx,"Site-data response limit");
    char *response=malloc((size_t)size+1);if(!response)return JS_ThrowOutOfMemory(ctx);
    if(fread(response,1,size,stdin)!=size){free(response);return JS_ThrowInternalError(ctx,"Site-data response ended");}
    response[size]=0;JSValue value=JS_NewStringLen(ctx,response,size);free(response);return value;
}
int main(void) {
#ifdef _WIN32
    _setmode(_fileno(stdin),_O_BINARY); _setmode(_fileno(stdout),_O_BINARY);
#endif
    JSRuntime *rt=JS_NewRuntime(); if(!rt) return 1;
    JS_SetMemoryLimit(rt,32*1024*1024); JS_SetMaxStackSize(rt,1024*1024); JS_SetInterruptHandler(rt,interrupted,NULL);
    JSContext *ctx=JS_NewContext(rt); if(!ctx) { JS_FreeRuntime(rt); return 1; }
    JSValue global=JS_GetGlobalObject(ctx); JS_SetPropertyStr(ctx,global,"__asterReadGamepads",JS_NewCFunction(ctx,gamepads,"__asterReadGamepads",0));
    JS_SetPropertyStr(ctx,global,"__asterSiteRequest",JS_NewCFunction(ctx,site_request,"__asterSiteRequest",1));JS_FreeValue(ctx,global);
    int kind; unsigned char header[4];
    while((kind=fgetc(stdin))!=EOF) {
        if(fread(header,1,4,stdin)!=4) break;
        uint32_t len=((uint32_t)header[0]<<24)|((uint32_t)header[1]<<16)|((uint32_t)header[2]<<8)|header[3];
        if(len>MAX_MESSAGE) break;
        char *source=malloc((size_t)len+1); if(!source) break;
        if(fread(source,1,len,stdin)!=len) { free(source); break; } source[len]=0;
        deadline=now_ms()+500;
        JSValue value;
        if(kind==2) { controller_enabled=(len==1 && source[0]=='1'); value=JS_NewBool(ctx,1); }
        else if(kind==1) {
            value=JS_Eval(ctx,source,len,"aster-page.js",JS_EVAL_TYPE_GLOBAL);
            JSContext *jobctx=ctx; int jobs=0;
            if(!JS_IsException(value)) while(JS_IsJobPending(rt) && jobs++<1024) {
                if(JS_ExecutePendingJob(rt,&jobctx)<0) { JS_FreeValue(ctx,value); value=JS_EXCEPTION; break; }
            }
            if(!JS_IsException(value) && JS_IsJobPending(rt)) { JS_FreeValue(ctx,value); value=JS_ThrowRangeError(ctx,"Promise job limit reached"); }
            if(JS_IsException(value)) {
                JSValue exception=JS_GetException(ctx); const char *error=JS_ToCString(ctx,exception);
                value=JS_NewObject(ctx); JS_SetPropertyStr(ctx,value,"error",JS_NewString(ctx,error ? error : "JavaScript failed"));
                if(error) JS_FreeCString(ctx,error); JS_FreeValue(ctx,exception);
            }
            if(JS_IsUndefined(value)) { JS_FreeValue(ctx,value); value=JS_NULL; }
        } else { free(source); break; }
        free(source); send_value(ctx,value); JS_FreeValue(ctx,value);
    }
    JS_FreeContext(ctx); JS_FreeRuntime(rt); return 0;
}
