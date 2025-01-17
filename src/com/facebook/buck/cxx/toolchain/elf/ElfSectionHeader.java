/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx.toolchain.elf;

import java.nio.ByteBuffer;

/** Encapsulate the data in an ELF section header. */
// CHECKSTYLE.OFF: LocalVariableName
// CHECKSTYLE.OFF: ParameterName
public class ElfSectionHeader {

  // CHECKSTYLE.OFF: MemberName
  public final long sh_name;
  public final SHType sh_type;
  public final long sh_flags;
  public final long sh_addr;
  public final long sh_off;
  public final long sh_size;
  public final long sh_link;
  public final long sh_info;
  public final long sh_addralign;
  public final long sh_entsize;
  // CHECKSTYLE.ON: MemberName

  public ElfSectionHeader(
      long sh_name,
      SHType sh_type,
      long sh_flags,
      long sh_addr,
      long sh_off,
      long sh_size,
      long sh_link,
      long sh_info,
      long sh_addralign,
      long sh_entsize) {
    this.sh_name = sh_name;
    this.sh_type = sh_type;
    this.sh_flags = sh_flags;
    this.sh_addr = sh_addr;
    this.sh_off = sh_off;
    this.sh_size = sh_size;
    this.sh_link = sh_link;
    this.sh_info = sh_info;
    this.sh_addralign = sh_addralign;
    this.sh_entsize = sh_entsize;
  }

  /** @return either a 32- or 64-bit ELF section header parsed from the given buffer. */
  static ElfSectionHeader parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
      return new ElfSectionHeader(
          buffer.getInt(),
          SHType.valueOf(buffer.getInt()),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt());
    } else {
      return new ElfSectionHeader(
          buffer.getInt(),
          SHType.valueOf(buffer.getInt()),
          buffer.getLong(),
          buffer.getLong(),
          buffer.getLong(),
          buffer.getLong(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getLong(),
          buffer.getLong());
    }
  }

  public void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
      buffer.putInt((int) sh_name);
      buffer.putInt(sh_type.getValue());
      buffer.putInt((int) sh_flags);
      buffer.putInt((int) sh_addr);
      buffer.putInt((int) sh_off);
      buffer.putInt((int) sh_size);
      buffer.putInt((int) sh_link);
      buffer.putInt((int) sh_info);
      buffer.putInt((int) sh_addralign);
      buffer.putInt((int) sh_entsize);
    } else {
      buffer.putInt((int) sh_name);
      buffer.putInt(sh_type.getValue());
      buffer.putLong(sh_flags);
      buffer.putLong(sh_addr);
      buffer.putLong(sh_off);
      buffer.putLong(sh_size);
      buffer.putInt((int) sh_link);
      buffer.putInt((int) sh_info);
      buffer.putLong(sh_addralign);
      buffer.putLong(sh_entsize);
    }
  }

  public ElfSectionHeader withSize(long size) {
    return new ElfSectionHeader(
        sh_name,
        sh_type,
        sh_flags,
        sh_addr,
        sh_off,
        size,
        sh_link,
        sh_info,
        sh_addralign,
        sh_entsize);
  }

  public ElfSectionHeader withInfo(long info) {
    return new ElfSectionHeader(
        sh_name,
        sh_type,
        sh_flags,
        sh_addr,
        sh_off,
        sh_size,
        sh_link,
        info,
        sh_addralign,
        sh_entsize);
  }

  public enum SHType {
    SHT_NULL(0),
    SHT_PROGBITS(1),
    SHT_SYMTAB(2),
    SHT_STRTAB(3),
    SHT_RELA(4),
    SHT_HASH(5),
    SHT_DYNAMIC(6),
    SHT_NOTE(7),
    SHT_NOBITS(8),
    SHT_REL(9),
    SHT_SHLIB(10),
    SHT_DYNSYM(11),
    SHT_RELR(19),

    // Android compressed relocations
    SHT_ANDROID_REL(0x60000001),
    SHT_ANDROID_RELA(0x60000002),

    // Android-scoped RELR
    SHT_ANDROID_RELR(0x6fffff00),

    // GNU-specific types.
    SHT_GNU_VERNEED(0x6ffffffe),
    SHT_GNU_VERDEF(0x6ffffffd),
    SHT_GNU_VERSYM(0x6fffffff),

    // Represents one of the user/processor specific values.
    SHT_UNKNOWN(0xffffffff),
    ;

    private final int value;

    SHType(int value) {
      this.value = value;
    }

    static SHType valueOf(int val) {
      for (SHType clazz : SHType.values()) {
        if (clazz.value == val) {
          return clazz;
        }
      }
      return SHT_UNKNOWN;
    }

    public int getValue() {
      return value;
    }
  }
}

// CHECKSTYLE.ON: ParameterName
// CHECKSTYLE.ON: LocalVariableName
