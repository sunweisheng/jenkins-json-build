//
//  Presenter.m
//  CICD-ObjectC-Test
//
//  Created by Test on 2020/8/17.
//  Copyright © 2020 Test. All rights reserved.
//

#import "TestPresenter.h"
#import "TestProtocol.h"

@interface TestPresenter ()

@property (nonatomic, weak, readwrite) id <TestProtocol> delegate;

@end

@implementation TestPresenter

- (instancetype)initWithDelegate:( nullable id <TestProtocol>)delegate {
    self = [super init];
    if (self) {
        _delegate = delegate;
    }
    return self;
}


- (void)personClickTestButton {
    if ([self.delegate respondsToSelector:@selector(protocolShowAlertMsg:)]) {
        [self.delegate protocolShowAlertMsg:@"Test 按钮被点击"];
    }
}

- (void)personClickCloseButton {
    if ([self.delegate respondsToSelector:@selector(protocolClickCloseButton)]) {
        [self.delegate protocolClickCloseButton];
    }
}

- (void)personClickConfirmButton {
    if ([self.delegate respondsToSelector:@selector(protocolClickConfirmButton)]) {
        [self.delegate protocolClickConfirmButton];
    }
}

@end
