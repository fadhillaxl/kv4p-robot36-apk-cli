CC = gcc
CFLAGS = -fPIC -Wall -O2
LDFLAGS = -shared

TARGET = librobot36.dylib
SRCS = robot36_decode.c
OBJS = $(SRCS:.c=.o)

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $^

%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	rm -f $(OBJS) $(TARGET)
