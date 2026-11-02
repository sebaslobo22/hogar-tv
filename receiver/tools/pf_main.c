#include <stdio.h>
#include <stdlib.h>
#include <string.h>
void playfair_decrypt(unsigned char* message3, unsigned char* cipherText, unsigned char* keyOut);
static void unhex(const char*h, unsigned char*o,int n){for(int i=0;i<n;i++){unsigned v;sscanf(h+2*i,"%2x",&v);o[i]=v;}}
int main(int argc,char**argv){unsigned char m[164],c[72],k[16];unhex(argv[1],m,164);unhex(argv[2],c,72);memset(k,0,16);playfair_decrypt(m,c,k);for(int i=0;i<16;i++)printf("%02x",k[i]);printf("\n");return 0;}
