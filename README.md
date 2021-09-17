# Fast Remapper

A Fast Java jar remapper.

Supports all mapping formats supported by [SrgUtils](https://github.com/MinecraftForge/SrgUtils).

The primary purpose of this tool is to remap Minecraft, specifically with Mojang official mappings. Please ensure you are adhering to Minecraft's EULA, and Proguard log Copyright notice.

### Usage:

```
Option (* = required)    Description                                         
---------------------    -----------                                         
-e, --exclude <String>   Excludes a class from being remapped. (startsWith   
                           check)                                            
-f, --flip               Flip the input mappings. (Useful for proguard logs) 
-h, --help               Prints this help                                    
* -i, --input <Path>     Sets the input jar.                                 
* -m, --mappings <Path>  The mappings to use. [Proguard,SRG,TSRG,TSRGv2,Tiny,
                           Tinyv2]                                           
* -o, --output <Path>    Sets the output jar.                                
-v, --verbose            Enables verbose logging. 
```

Client Example: `java -jar FastRemapper-all.jar --input 1.16.5-client.jar --mappings 1.16.5-client.txt --flip --output 1.16.5-client-mapped.jar`  
Server Example: `java -jar FastRemapper-all.jar --input 1.16.5-server.jar --mappings 1.16.5-server.txt --flip --output 1.16.5-server-mapped.jar --exclude "com.google.,com.mojang.,io.netty.,it.unimi.dsi.fastutil.,javax.,joptsimple.,org.apache."`

### Noteworthy items:

- `--flip` should be used when dealing with proguard logs (Official mappings).
- `--exclude` can be used to exclude individual files, or packages. Supports comma seperated list of partial or full class names. (String.startsWith)

### Limitations:

- Does not support mapping with reference libraries for inheritance. (Needed for remapping Mods or libraries.)
- Does not support formats which contain parameter or local variable names.

### Builds:

Builds can be downloaded directly from [maven](https://maven.covers1624.net/net/covers1624/FastRemapper).  
If you intend to run the tool standalone from the command line use the '-all.jar'.

### TODO:

- [ ] Reference libraries for inheritance.
- [ ] Configurable local variable rewriting.
- [ ] Make it faster!
 
This project is not affiliated with Minecraft, Mojang, or Microsoft.
