#include <stdio.h>
#include <stdint.h>
#include "zstd-aws/build-23/benchmark_data_3.h"
unsigned char * compressed_data = benchmark_compressed_data_3;
unsigned char * uncompressed_data = benchmark_uncompressed_data_3;
unsigned int compressed_data_len = 569;

void getinfo(){
    unsigned int bytecount = 0;
    printf("Magic Number: ");
    for(int i=0; i<4; ++i){printf("%02x ",compressed_data[i]);}
    printf("\n");
    bytecount = 4;
    unsigned int realbytecount;

    // Decompress Frame Header
    unsigned int dictid, checksum, reserved, singlesegment, fcs;
    unsigned int windowsize, exponent, mantissa;
    unsigned int did;
    unsigned long long fcssize;
    printf("Frame Info\n");
    dictid=compressed_data[4]%4;
    checksum=(compressed_data[4]>>2)%2;
    reserved=(compressed_data[4]>>3)%2;
    singlesegment=(compressed_data[4]>>5)%2;
    fcs=(compressed_data[4]>>6)%4;
    printf("Dict ID: %d, checksum: %d, reserved: %d\n", dictid, checksum, reserved);
    printf("Single segment: %d, FCS: %d\n", singlesegment, fcs);
    ++bytecount;

    if(singlesegment!=1){
        printf("Single segment is not 1!\n");
        exponent = compressed_data[bytecount]>>3;
        mantissa = compressed_data[bytecount]%8;
        windowsize = (1<<(10+exponent)) * (1 + mantissa);
        ++bytecount;
    }
    if(dictid!=0){
        printf("Dict ID is not 0!\n");
        if(dictid==1){
            did = compressed_data[bytecount];
            bytecount += 1;
        }
        else if(dictid==2){
            did = compressed_data[bytecount+1]*256 + compressed_data[bytecount];
            bytecount += 2;
        }
        else{
            did = compressed_data[bytecount+3]*256*256*256 + compressed_data[bytecount+2]*256*256 + compressed_data[bytecount+1]*256 + compressed_data[bytecount];
            bytecount += 4;
        }
    }
    switch(fcs){
        case 0: fcssize=compressed_data[bytecount];
            bytecount += 1; break;
        case 1: fcssize=compressed_data[bytecount+1]*256 + compressed_data[bytecount];
            bytecount += 2; break;
        case 2: fcssize=compressed_data[bytecount+3]*256*256*256 + compressed_data[bytecount+2]*256*256 + compressed_data[bytecount+1]*256 + compressed_data[bytecount]; 
            bytecount += 4; break;
        case 3: printf("FCS is 3!! too big!!\n"); break;
    }
    printf("FCS size: %llu\n", fcssize);
    realbytecount=bytecount;

    int blocknum = 1;
    int islastblock; int blocktype; int blocksize;
    int litblocktype, litsizeformat, litcompsize, litdecompsize;
    int byte0, byte1, byte2, num_sequences, llmode, offmode, mlmode;
    unsigned long long totalcompsize = checksum ? compressed_data_len-4: compressed_data_len;
    for(blocknum=1;realbytecount<totalcompsize;++blocknum){
        printf("Block %d\n", blocknum);
        bytecount = realbytecount;
        //Decompress block header
        islastblock = compressed_data[bytecount]%2;
        blocktype = (compressed_data[bytecount]>>1)%4;
        blocksize = compressed_data[bytecount+2]*8192 + \
            compressed_data[bytecount+1]*32 + \
            (compressed_data[bytecount]>>3);
        printf("Last block: %d, Zstd block type: %d, block size: %d", \
            islastblock, blocktype, blocksize);
        bytecount += 3; // Block header size
        printf("\n");

        //Decompress literal header
        litblocktype = compressed_data[bytecount]%4;
        printf("Huffman block type: %d, ", litblocktype);
        litsizeformat = (compressed_data[bytecount]>>2)%4;
        if(litblocktype<2){
            if(litsizeformat==1){
                litdecompsize = (compressed_data[bytecount]>>4) + \
                    compressed_data[bytecount+1]*16;
                bytecount += 2; // Literal section header size
            }
            else if(litsizeformat==3){
                litdecompsize = (compressed_data[bytecount]>>4) +\
                    compressed_data[bytecount+1]*16 + \
                    compressed_data[bytecount+2]*4096;
                bytecount += 3; // Literal section header size
            }
            else{
                litdecompsize = compressed_data[bytecount]>>3;
                bytecount += 1; // Literal section header size
            }
            
            if(litblocktype==1){litcompsize=1;} // Literal decoded size
            else {litcompsize=litdecompsize;}
        }
        else{
            if(litsizeformat<2){
                litdecompsize = (compressed_data[bytecount]>>4) + (compressed_data[bytecount+1]%64)*16; //6B
                litcompsize = (compressed_data[bytecount+1]>>6) + compressed_data[bytecount+2]*4; //8B
                bytecount += 3; // Literal section header size
            }
            else if(litsizeformat==2){
                litdecompsize = (compressed_data[bytecount]>>4) + compressed_data[bytecount+1]*16 + (compressed_data[bytecount+2]%4)*4096; //2B
                litcompsize = (compressed_data[bytecount+2]>>2) + compressed_data[bytecount+3]*64; //8B
                bytecount += 4; // Literal section header size
            }
            else{
                litdecompsize = (compressed_data[bytecount]>>4) + compressed_data[bytecount+1]*16 + (compressed_data[bytecount+2]%64)*4096; //6B
                litcompsize = (compressed_data[bytecount+2]>>6) + compressed_data[bytecount+3]*4 + compressed_data[bytecount+4]*1024; //8B
                bytecount += 5; // Literal section header size
            }
        }
        bytecount += litcompsize;
        printf("Huffman size format: %d, ", litsizeformat);
        printf("Literal comp size: %d, ", litcompsize);
        printf("Literal decomp size: %d\n", litdecompsize);

        //Decompress sequence header
        byte0 = compressed_data[bytecount];
        byte1 = compressed_data[bytecount+1];
        byte2 = compressed_data[bytecount+2];
        if(byte0==0){printf("No sequences!\n"); bytecount+=1;}
        else if(byte0<128){num_sequences = byte0; bytecount+=1;}
        else if(byte0<255){num_sequences = (byte0-128)*256 + byte1; bytecount+=2;}
        else{num_sequences = byte1 + byte2*256 + 32512; bytecount+=3;}
        llmode = compressed_data[bytecount]>>6;
        offmode = (compressed_data[bytecount]>>4)%4;
        mlmode = (compressed_data[bytecount]>>2)%4;
        printf("FSE num_sequences: %d, FSE modes: %d %d %d",\
            num_sequences, llmode, offmode, mlmode);
        //Increment bytecount
        realbytecount += (3+blocksize);
        printf("\n");
        printf("Compressed data len: %llu, bytes consumed so far: %llu\n", compressed_data_len, realbytecount);
    }    
}
void printheader(){
    //Magic Number 4B
    //Frame Header 1B, usually skip Dict ID and window size
    //FCS Size usually 4B
    //Block Header 3B, Block size xB.
    int n=32;
    for(int i=0;i<n;++i){
        printf("%02x ", compressed_data[768-32+i]);
        // printf("%02x ", uncompressed_data[131072-n+i]);
        if(i%8==7) printf("\n");
    }        
    printf("\n");
}
int main(){
    // printheader();
    getinfo();
}